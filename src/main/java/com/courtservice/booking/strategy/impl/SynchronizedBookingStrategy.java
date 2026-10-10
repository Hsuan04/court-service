package com.courtservice.booking.strategy.impl;

import com.courtservice.booking.entity.Booking;
import com.courtservice.booking.entity.BookingStatus;
import com.courtservice.booking.repository.BookingRepository;
import com.courtservice.booking.strategy.BookingConcurrencyMode;
import com.courtservice.booking.strategy.BookingOutcome;
import com.courtservice.booking.strategy.BookingStrategy;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.member.Member;
import com.courtservice.member.MemberService;
import com.courtservice.session.CourtSession;
import com.courtservice.session.CourtSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JVM-wide mutual exclusion: every booking, for any session, runs one at a time behind a single
 * {@code synchronized} lock, so the read-check-write of {@code bookedCount} can no longer interleave.
 */
@Slf4j
@Component
public class SynchronizedBookingStrategy implements BookingStrategy {

    /** A private monitor, so no other code can synchronize on it and interfere with the queue. */
    private final Object lock = new Object();

    private final CourtSessionService courtSessionService;
    private final MemberService memberService;
    private final BookingRepository bookingRepository;
    private final TransactionTemplate transactionTemplate;

    public SynchronizedBookingStrategy(CourtSessionService courtSessionService,
                                       MemberService memberService,
                                       BookingRepository bookingRepository,
                                       TransactionTemplate transactionTemplate) {
        this.courtSessionService = courtSessionService;
        this.memberService = memberService;
        this.bookingRepository = bookingRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public BookingConcurrencyMode mode() {
        return BookingConcurrencyMode.SYNCHRONIZED;
    }

    /**
     * Books a seat while holding the JVM-wide lock from before the transaction begins until after
     * it commits or rolls back.
     */
    @Override
    public BookingOutcome book(Long courtSessionId, Long memberId) {
        synchronized (lock) {
            return transactionTemplate.execute(status -> doBook(courtSessionId, memberId));
        }
    }

    private BookingOutcome doBook(Long courtSessionId, Long memberId) {
        // A plain read is enough: no other booking in this JVM can run until this one commits.
        CourtSession session = courtSessionService.findSession(courtSessionId);
        Member member = memberService.findMember(memberId);

        if (bookingRepository.existsByCourtSessionIdAndMemberIdAndStatus(courtSessionId, memberId,
                BookingStatus.CONFIRMED)) {
            throw new BusinessException(ErrorCode.DUPLICATE_BOOKING, ErrorCode.DUPLICATE_BOOKING.getDefaultDetail());
        }

        if (session.isFull()) {
            log.info("Session {} member {}: session full ({} of {} seats taken)", courtSessionId, memberId,
                    session.getBookedCount(), session.getCapacity());
            throw new BusinessException(ErrorCode.SESSION_FULL, ErrorCode.SESSION_FULL.getDefaultDetail());
        }
        // The new count is flushed on commit, which still happens inside the lock.
        session.book();
        Booking booking = bookingRepository.save(new Booking(session, member));

        return new BookingOutcome(booking, session.getCapacity(), session.getBookedCount());
    }
}
