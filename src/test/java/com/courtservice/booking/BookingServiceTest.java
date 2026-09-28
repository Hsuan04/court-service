package com.courtservice.booking;

import com.courtservice.booking.dto.BookingCreateRequest;
import com.courtservice.booking.strategy.BookingStrategy;
import com.courtservice.booking.strategy.BookingStrategyRegistry;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.member.MemberService;
import com.courtservice.session.CourtSessionService;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the translation of the booking unique constraint, which only a real race can trigger;
 * stubbing the strategy makes that path deterministic.
 */
class BookingServiceTest {

    private static final long SESSION_ID = 1L;
    private static final long MEMBER_ID = 2L;
    private static final BookingCreateRequest REQUEST = new BookingCreateRequest(SESSION_ID, MEMBER_ID);

    private BookingStrategy strategy;
    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        strategy = mock(BookingStrategy.class);
        BookingStrategyRegistry registry = mock(BookingStrategyRegistry.class);
        when(registry.active()).thenReturn(strategy);
        bookingService = new BookingService(mock(BookingRepository.class), mock(CourtSessionService.class),
                mock(MemberService.class), registry);
    }

    @Test
    void createTranslatesTheSessionMemberUniqueViolationIntoDuplicateBooking() {
        // given
        when(strategy.book(SESSION_ID, MEMBER_ID))
                .thenThrow(constraintViolation(Booking.SESSION_MEMBER_UNIQUE_CONSTRAINT));

        // when / then
        assertThatThrownBy(() -> bookingService.create(REQUEST))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_BOOKING);
    }

    @Test
    void createRethrowsOtherConstraintViolationsUnchanged() {
        // given
        DataIntegrityViolationException violation = constraintViolation("booking_member_id_fkey");
        when(strategy.book(SESSION_ID, MEMBER_ID)).thenThrow(violation);

        // when / then
        assertThatThrownBy(() -> bookingService.create(REQUEST)).isSameAs(violation);
    }

    private static DataIntegrityViolationException constraintViolation(String constraintName) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("constraint violated", new SQLException(), constraintName));
    }
}
