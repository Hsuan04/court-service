package com.courtservice.booking.repository;

import com.courtservice.booking.entity.Booking;
import com.courtservice.booking.entity.BookingStatus;
import com.courtservice.support.IntegrationTestData;
import com.courtservice.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks the partial unique index {@code uq_booking_session_member} from migration V2 directly in
 * the database: only confirmed bookings are unique per session and member, so a cancelled booking
 * does not stop the member from booking the session again.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BookingConstraintIntegrationTest {

    private static final int CAPACITY = 10;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BookingRepository bookingRepository;

    private IntegrationTestData data;
    private long sessionId;
    private long memberId;

    @BeforeEach
    void setUp() {
        data = new IntegrationTestData(jdbcTemplate);
        data.truncateAll();
        sessionId = data.insertSession(data.insertCourt("Court"), CAPACITY);
        memberId = data.insertMember("Member");
    }

    @Test
    void confirmedBookingMayExistNextToCancelledOnesOfTheSameMember() {
        // given
        data.insertBooking(sessionId, memberId, BookingStatus.CANCELLED.name());
        data.insertBooking(sessionId, memberId, BookingStatus.CANCELLED.name());

        // when
        data.insertBooking(sessionId, memberId, BookingStatus.CONFIRMED.name());

        // then
        assertThat(bookingRepository.existsByCourtSessionIdAndMemberIdAndStatus(sessionId, memberId,
                BookingStatus.CONFIRMED)).isTrue();
        assertThat(bookingRepository.findMemberIdsByCourtSessionIdAndStatus(sessionId, BookingStatus.CONFIRMED))
                .containsExactly(memberId);
    }

    @Test
    void secondConfirmedBookingOfTheSameMemberIsRejectedByTheNamedIndex() {
        // given
        data.insertBooking(sessionId, memberId, BookingStatus.CONFIRMED.name());

        // when / then
        assertThatThrownBy(() -> data.insertBooking(sessionId, memberId, BookingStatus.CONFIRMED.name()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(Booking.SESSION_MEMBER_UNIQUE_CONSTRAINT);
    }

    @Test
    void cancelledBookingsAreNotCountedAsBooked() {
        // given
        data.insertBooking(sessionId, memberId, BookingStatus.CANCELLED.name());

        // when / then
        assertThat(bookingRepository.existsByCourtSessionIdAndMemberIdAndStatus(sessionId, memberId,
                BookingStatus.CONFIRMED)).isFalse();
        assertThat(bookingRepository.findMemberIdsByCourtSessionIdAndStatus(sessionId, BookingStatus.CONFIRMED))
                .isEmpty();
    }
}
