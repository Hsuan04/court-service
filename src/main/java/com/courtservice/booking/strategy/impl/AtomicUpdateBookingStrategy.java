package com.courtservice.booking.strategy.impl;

import com.courtservice.booking.Booking;
import com.courtservice.booking.BookingRepository;
import com.courtservice.booking.strategy.BookingConcurrencyMode;
import com.courtservice.booking.strategy.BookingOutcome;
import com.courtservice.booking.strategy.BookingStrategy;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.member.Member;
import com.courtservice.member.MemberService;
import com.courtservice.session.BookedCountSnapshot;
import com.courtservice.session.CourtSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Component
public class AtomicUpdateBookingStrategy implements BookingStrategy {

    private final CourtSessionService courtSessionService;
    private final MemberService memberService;
    private final BookingRepository bookingRepository;
    private final TransactionTemplate transactionTemplate;

    public AtomicUpdateBookingStrategy(CourtSessionService courtSessionService,
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
        return BookingConcurrencyMode.ATOMIC_UPDATE;
    }

    @Override
    public BookingOutcome book(Long courtSessionId, Long memberId) {
        return transactionTemplate.execute(status -> doBook(courtSessionId, memberId));
    }

    private BookingOutcome doBook(Long courtSessionId, Long memberId) {

        //1. get member by memberId
        Member member = memberService.findMember(memberId);

        //2. check member is already booking the session
        if (bookingRepository.existsByCourtSessionIdAndMemberId(courtSessionId, memberId)) {
            throw new BusinessException(ErrorCode.DUPLICATE_BOOKING, ErrorCode.DUPLICATE_BOOKING.getDefaultDetail());
        }

        // Check-and-increment in one statement; empty means the session is full or does not exist.
        BookedCountSnapshot counters = courtSessionService.incrementBookedCountIfAvailable(courtSessionId)
                .orElseThrow(() -> fullOrMissing(courtSessionId, memberId));

        // The UPDATE proved the session exists, so a reference is enough to set the foreign key.
        Booking booking = bookingRepository.save(
                new Booking(courtSessionService.getSessionReference(courtSessionId), member));

        return new BookingOutcome(booking, counters.getCapacity(), counters.getBookedCount());
    }

    /**
     * Explains an UPDATE that matched no row: a missing session and a full one look the same to
     * the conditional statement, so the existence check tells them apart.
     *
     * @throws com.courtservice.common.error.ResourceNotFoundException if the session does not exist
     */
    private BusinessException fullOrMissing(Long courtSessionId, Long memberId) {
        courtSessionService.verifyExists(courtSessionId);
        log.info("Session {} member {}: session full, conditional update matched no row", courtSessionId, memberId);
        return new BusinessException(ErrorCode.SESSION_FULL, ErrorCode.SESSION_FULL.getDefaultDetail());
    }
}
