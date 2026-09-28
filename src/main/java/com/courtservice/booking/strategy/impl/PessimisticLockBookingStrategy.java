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
public class PessimisticLockBookingStrategy implements BookingStrategy {

    private static final Logger log = LoggerFactory.getLogger(PessimisticLockBookingStrategy.class);

    private final CourtSessionService courtSessionService;
    private final MemberService memberService;
    private final BookingRepository bookingRepository;
    private final TransactionTemplate transactionTemplate;


    public PessimisticLockBookingStrategy(CourtSessionService courtSessionService,
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
        return BookingConcurrencyMode.PESSIMISTIC_LOCK;
    }

    @Override
    public BookingOutcome book(Long courtSessionId, Long memberId) {
        return transactionTemplate.execute(status -> doBook(courtSessionId, memberId));
    }

    private BookingOutcome doBook(Long courtSessionId, Long memberId) {

        //1. find session from DB
        CourtSession session = courtSessionService.findSessionForUpdate(courtSessionId);

        //2. find member from DB
        Member member = memberService.findMember(memberId);

        //3. duplicate check booking same member
        if (bookingRepository.existsByCourtSessionIdAndMemberId(courtSessionId, memberId)) {
            throw new BusinessException(ErrorCode.DUPLICATE_BOOKING,
                    ErrorCode.DUPLICATE_BOOKING.getDefaultDetail());
        }

        //4. take a seat (throws SESSION_FULL) and create booking
        session.book();
        Booking booking = bookingRepository.save(new Booking(session, member));

        return new BookingOutcome(booking, session.getCapacity(), session.getBookedCount());
    }
}
