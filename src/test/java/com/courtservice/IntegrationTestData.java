package com.courtservice;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Prepares database state for API integration tests through plain JDBC, so that fixtures
 * bypass the API under test and can set columns the API never writes, such as
 * {@code booked_count} or bookings.
 */
final class IntegrationTestData {

    static final LocalDate SESSION_DATE = LocalDate.of(2026, 10, 1);
    static final LocalTime START_TIME = LocalTime.of(18, 0);
    static final LocalTime END_TIME = LocalTime.of(20, 0);
    static final OffsetDateTime OPEN_AT = OffsetDateTime.of(2026, 9, 25, 0, 0, 0, 0, ZoneOffset.UTC);
    static final OffsetDateTime CLOSE_AT = OffsetDateTime.of(2026, 9, 30, 23, 59, 59, 0, ZoneOffset.UTC);
    static final long MISSING_ID = 999_999L;

    private static final String CONFIRMED = "CONFIRMED";

    private final JdbcTemplate jdbcTemplate;

    IntegrationTestData(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Removes all rows so each test starts from an empty database, independent of test order. */
    void truncateAll() {
        jdbcTemplate.execute("TRUNCATE booking, court_session, member, court RESTART IDENTITY CASCADE");
    }

    long insertCourt(String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO court (name, address) VALUES (?, ?) RETURNING id", Long.class, name, "Taipei");
    }

    long insertMember(String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO member (name) VALUES (?) RETURNING id", Long.class, name);
    }

    long insertSession(long courtId, int capacity) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO court_session (court_id, session_date, start_time, end_time, capacity, open_at, close_at)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, courtId, SESSION_DATE, START_TIME, END_TIME, capacity, OPEN_AT, CLOSE_AT);
    }

    long insertBooking(long courtSessionId, long memberId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO booking (court_session_id, member_id, status) VALUES (?, ?, ?) RETURNING id",
                Long.class, courtSessionId, memberId, CONFIRMED);
    }

    void setBookedCount(long courtSessionId, int bookedCount) {
        jdbcTemplate.update("UPDATE court_session SET booked_count = ? WHERE id = ?", bookedCount, courtSessionId);
    }

    int bookedCount(long courtSessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT booked_count FROM court_session WHERE id = ?", Integer.class, courtSessionId);
    }

    int countRows(String table, long id) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id);
    }
}
