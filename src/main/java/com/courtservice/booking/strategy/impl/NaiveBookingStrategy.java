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
import com.courtservice.session.CourtSession;
import com.courtservice.session.CourtSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class NaiveBookingStrategy implements BookingStrategy {

    private static final Logger log = LoggerFactory.getLogger(NaiveBookingStrategy.class);

    private final CourtSessionService courtSessionService;
    private final MemberService memberService;
    private final BookingRepository bookingRepository;
    private final TransactionTemplate transactionTemplate;

    public NaiveBookingStrategy(CourtSessionService courtSessionService,
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
        return BookingConcurrencyMode.NAIVE;
    }

    @Override
    public BookingOutcome book(Long courtSessionId, Long memberId) {
        return transactionTemplate.execute(status -> doBook(courtSessionId, memberId));
    }

    private BookingOutcome doBook(Long courtSessionId, Long memberId) {
        // 1. Read (404 if missing)
        CourtSession session = courtSessionService.findSession(courtSessionId);
        Member member = memberService.findMember(memberId);

        // 2. Check duplicate: itself a check-then-act race
        if (bookingRepository.existsByCourtSessionIdAndMemberId(courtSessionId, memberId)) {
            throw new BusinessException(ErrorCode.DUPLICATE_BOOKING, ErrorCode.DUPLICATE_BOOKING.getDefaultDetail());
        }

        // 3. Check capacity against the value we read: other threads may have read the same value
        int readCount = session.getBookedCount();
        int capacity = session.getCapacity();
        if (readCount >= capacity) {
            log.info("Session {} full: bookedCount={}, capacity={}", courtSessionId, readCount, capacity);
            throw new BusinessException(ErrorCode.SESSION_FULL, ErrorCode.SESSION_FULL.getDefaultDetail());
        }

        // 4. Write booking
        Booking booking = bookingRepository.save(new Booking(session, member));

        // 5. Write back "read value + 1" as an absolute value: lost update under concurrency
        int newCount = readCount + 1;
        courtSessionService.overwriteBookedCount(courtSessionId, newCount);

        return new BookingOutcome(booking, capacity, newCount);
    }
}
