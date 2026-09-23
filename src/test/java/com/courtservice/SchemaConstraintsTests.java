package com.courtservice;

import com.courtservice.booking.Booking;
import com.courtservice.booking.BookingRepository;
import com.courtservice.court.Court;
import com.courtservice.court.CourtRepository;
import com.courtservice.member.Member;
import com.courtservice.member.MemberRepository;
import com.courtservice.session.CourtSession;
import com.courtservice.session.CourtSessionRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class SchemaConstraintsTests {

    @Autowired
    private CourtRepository courtRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CourtSessionRepository courtSessionRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Test
    void applicationStartsWithMigratedAndValidatedSchema() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
    }

    @Test
    void sameMemberCannotBookSameSessionTwice() {
        CourtSession session = saveSession(10);
        Member member = memberRepository.saveAndFlush(new Member("Alice"));
        bookingRepository.saveAndFlush(new Booking(session, member));

        assertThatThrownBy(() -> bookingRepository.saveAndFlush(new Booking(session, member)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_booking_session_member");
    }

    @Test
    void bookedCountCannotExceedCapacity() {
        CourtSession session = saveSession(2);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE court_session SET booked_count = capacity + 1 WHERE id = ?", session.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_session_booked");
    }

    @Test
    void bookingStatusRejectsUnknownValue() {
        CourtSession session = saveSession(10);
        Member member = memberRepository.saveAndFlush(new Member("Bob"));
        Booking booking = bookingRepository.saveAndFlush(new Booking(session, member));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE booking SET status = 'PENDING' WHERE id = ?", booking.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_booking_status");
    }

    private CourtSession saveSession(int capacity) {
        Court court = courtRepository.saveAndFlush(new Court("Court A", "Taipei"));
        OffsetDateTime openAt = OffsetDateTime.now();
        return courtSessionRepository.saveAndFlush(new CourtSession(
                court,
                LocalDate.now().plusDays(7),
                LocalTime.of(18, 0),
                LocalTime.of(20, 0),
                capacity,
                openAt,
                openAt.plusDays(6)));
    }
}
