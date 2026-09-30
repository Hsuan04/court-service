package com.courtservice.session;

import com.courtservice.TestcontainersConfiguration;
import com.courtservice.IntegrationTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CourtSessionIntegrationTest {

    private static final int DEFAULT_PAGE_SIZE = 20;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CourtSessionService courtSessionService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private IntegrationTestData data;

    private long courtId;

    @BeforeEach
    void setUp() {
        data = new IntegrationTestData(jdbcTemplate);
        data.truncateAll();
        courtId = data.insertCourt("Court A");
    }

    @Test
    void bookSeatWritesTheSeatBeforeTheTransactionCommits() {
        // given
        long id = data.insertSession(courtId, 10);

        // when
        Integer bookedCountInsideTransaction = transactionTemplate.execute(status -> {
            courtSessionService.bookSeat(courtSessionService.findSession(id));
            // JdbcTemplate shares the transaction's connection, so it sees the flushed UPDATE.
            return data.bookedCount(id);
        });

        // then
        assertThat(bookedCountInsideTransaction).isEqualTo(1);
        assertThat(data.bookedCount(id)).isEqualTo(1);
    }

    @Test
    void incrementBookedCountIfAvailableTakesASeatAndBumpsTheVersion() {
        // given
        long id = data.insertSession(courtId, 10);
        data.setBookedCount(id, 3);

        // when
        Optional<BookedCountSnapshot> snapshot = transactionTemplate.execute(
                status -> courtSessionService.incrementBookedCountIfAvailable(id));

        // then
        assertThat(snapshot).hasValueSatisfying(counters -> {
            assertThat(counters.getBookedCount()).isEqualTo(4);
            assertThat(counters.getCapacity()).isEqualTo(10);
        });
        assertThat(data.bookedCount(id)).isEqualTo(4);
        assertThat(version(id)).isEqualTo(1);
    }

    @Test
    void incrementBookedCountIfAvailableLeavesAFullSessionUntouched() {
        // given
        long id = data.insertSession(courtId, 10);
        data.setBookedCount(id, 10);

        // when
        Optional<BookedCountSnapshot> snapshot = transactionTemplate.execute(
                status -> courtSessionService.incrementBookedCountIfAvailable(id));

        // then
        assertThat(snapshot).isEmpty();
        assertThat(data.bookedCount(id)).isEqualTo(10);
        assertThat(version(id)).isZero();
    }

    @Test
    void incrementBookedCountIfAvailableReturnsEmptyForUnknownSession() {
        Optional<BookedCountSnapshot> snapshot = transactionTemplate.execute(
                status -> courtSessionService.incrementBookedCountIfAvailable(IntegrationTestData.MISSING_ID));

        assertThat(snapshot).isEmpty();
    }

    /**
     * Guards the version bump of the atomic update: without it, a copy of the session read before
     * the update would be saved back with its stale booked_count and silently lose the booking.
     */
    @Test
    void atomicIncrementMakesAStaleCopyFailInsteadOfOverwritingBookedCount() {
        // given
        long id = data.insertSession(courtId, 10);
        TransactionTemplate otherTransaction = new TransactionTemplate(transactionManager);
        otherTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // when / then
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            CourtSession stale = courtSessionService.findSession(id);
            otherTransaction.executeWithoutResult(
                    inner -> courtSessionService.incrementBookedCountIfAvailable(id));
            stale.changeCapacity(20);
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(data.bookedCount(id)).isEqualTo(1);
    }

    @Test
    void bookSeatFailsWhenTheSessionChangedAfterItWasRead() {
        // given
        long id = data.insertSession(courtId, 10);

        // when / then
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            CourtSession session = courtSessionService.findSession(id);
            // Stands in for another transaction that committed a change after this read.
            jdbcTemplate.update("UPDATE court_session SET version = version + 1 WHERE id = ?", id);
            courtSessionService.bookSeat(session);
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(data.bookedCount(id)).isZero();
    }

    @Test
    void createReturnsCreatedWithLocationAndZeroBookedCount() throws Exception {
        mockMvc.perform(post("/api/court-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(courtId, "18:00", "20:00", 10,
                                "2026-09-25T00:00:00Z", "2026-09-30T23:59:59Z")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/court-sessions/1")))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.courtId").value(courtId))
                .andExpect(jsonPath("$.sessionDate").value("2026-10-01"))
                .andExpect(jsonPath("$.startTime").value("18:00:00"))
                .andExpect(jsonPath("$.endTime").value("20:00:00"))
                .andExpect(jsonPath("$.capacity").value(10))
                .andExpect(jsonPath("$.bookedCount").value(0))
                .andExpect(jsonPath("$.openAt").exists())
                .andExpect(jsonPath("$.closeAt").exists());

        assertThat(data.countRows("court_session", 1)).isEqualTo(1);
    }

    @Test
    void createIgnoresBookedCountInRequest() throws Exception {
        String body = """
                {"courtId": %d, "sessionDate": "2026-10-01", "startTime": "18:00", "endTime": "20:00",
                 "capacity": 10, "bookedCount": 7, "openAt": "2026-09-25T00:00:00Z",
                 "closeAt": "2026-09-30T23:59:59Z"}
                """.formatted(courtId);

        mockMvc.perform(post("/api/court-sessions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookedCount").value(0));

        assertThat(data.bookedCount(1)).isZero();
    }

    @Test
    void createReturnsNotFoundForUnknownCourt() throws Exception {
        mockMvc.perform(post("/api/court-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(IntegrationTestData.MISSING_ID, "18:00", "20:00", 10,
                                "2026-09-25T00:00:00Z", "2026-09-30T23:59:59Z")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void createRejectsMissingFieldsAndNonPositiveCapacity() throws Exception {
        mockMvc.perform(post("/api/court-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"capacity": 0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder(
                        "courtId", "sessionDate", "startTime", "endTime", "capacity", "openAt", "closeAt")));
    }

    @Test
    void createRejectsEndTimeNotAfterStartTime() throws Exception {
        mockMvc.perform(post("/api/court-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(courtId, "20:00", "18:00", 10,
                                "2026-09-25T00:00:00Z", "2026-09-30T23:59:59Z")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
    }

    @Test
    void createRejectsCloseAtNotAfterOpenAt() throws Exception {
        mockMvc.perform(post("/api/court-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(courtId, "18:00", "20:00", 10,
                                "2026-09-30T23:59:59Z", "2026-09-30T23:59:59Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
    }

    @Test
    void getReturnsSession() throws Exception {
        long id = data.insertSession(courtId, 10);
        data.setBookedCount(id, 3);

        mockMvc.perform(get("/api/court-sessions/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.courtId").value(courtId))
                .andExpect(jsonPath("$.capacity").value(10))
                .andExpect(jsonPath("$.bookedCount").value(3));
    }

    @Test
    void getReturnsNotFoundForUnknownId() throws Exception {
        mockMvc.perform(get("/api/court-sessions/{id}", IntegrationTestData.MISSING_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void listWithoutParametersUsesDefaultPageSizeSortedByIdAscending() throws Exception {
        long first = data.insertSession(courtId, 10);
        long second = data.insertSession(courtId, 20);

        mockMvc.perform(get("/api/court-sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].id").value(first))
                .andExpect(jsonPath("$.content[0].courtId").value(courtId))
                .andExpect(jsonPath("$.content[1].id").value(second))
                .andExpect(jsonPath("$.page.size").value(DEFAULT_PAGE_SIZE))
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    void listWithPageAndSizeReturnsRequestedPage() throws Exception {
        data.insertSession(courtId, 10);
        data.insertSession(courtId, 10);
        long third = data.insertSession(courtId, 10);

        mockMvc.perform(get("/api/court-sessions").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(third))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(3));
    }

    @Test
    void updateReplacesScheduleAndCapacityButKeepsCourt() throws Exception {
        long id = data.insertSession(courtId, 10);

        mockMvc.perform(put("/api/court-sessions/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("2026-10-02", "08:00", "09:30", 30,
                                "2026-09-26T00:00:00Z", "2026-10-01T00:00:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.courtId").value(courtId))
                .andExpect(jsonPath("$.sessionDate").value("2026-10-02"))
                .andExpect(jsonPath("$.startTime").value("08:00:00"))
                .andExpect(jsonPath("$.endTime").value("09:30:00"))
                .andExpect(jsonPath("$.capacity").value(30));

        assertThat(jdbcTemplate.queryForObject("SELECT capacity FROM court_session WHERE id = ?", Integer.class, id))
                .isEqualTo(30);
        assertThat(jdbcTemplate.queryForObject("SELECT end_time FROM court_session WHERE id = ?", LocalTime.class, id))
                .isEqualTo(LocalTime.of(9, 30));
        assertThat(jdbcTemplate.queryForObject("SELECT close_at FROM court_session WHERE id = ?",
                OffsetDateTime.class, id).toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-10-01T00:00:00Z").toInstant());
    }

    @Test
    void updateIgnoresBookedCountInRequest() throws Exception {
        long id = data.insertSession(courtId, 10);
        data.setBookedCount(id, 2);
        String body = """
                {"sessionDate": "2026-10-01", "startTime": "18:00", "endTime": "20:00", "capacity": 10,
                 "bookedCount": 9, "openAt": "2026-09-25T00:00:00Z", "closeAt": "2026-09-30T23:59:59Z"}
                """;

        mockMvc.perform(put("/api/court-sessions/{id}", id).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookedCount").value(2));

        assertThat(data.bookedCount(id)).isEqualTo(2);
    }

    @Test
    void updateRejectsInvalidTimeRange() throws Exception {
        long id = data.insertSession(courtId, 10);

        mockMvc.perform(put("/api/court-sessions/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("2026-10-01", "18:00", "18:00", 10,
                                "2026-09-25T00:00:00Z", "2026-09-30T23:59:59Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));

        assertThat(jdbcTemplate.queryForObject("SELECT end_time FROM court_session WHERE id = ?", LocalTime.class, id))
                .isEqualTo(IntegrationTestData.END_TIME);
    }

    @Test
    void updateRejectsCapacityBelowBookedCount() throws Exception {
        long id = data.insertSession(courtId, 10);
        data.setBookedCount(id, 5);

        mockMvc.perform(put("/api/court-sessions/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("2026-10-02", "18:00", "20:00", 4,
                                "2026-09-25T00:00:00Z", "2026-09-30T23:59:59Z")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));

        assertThat(jdbcTemplate.queryForObject("SELECT capacity FROM court_session WHERE id = ?", Integer.class, id))
                .isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject("SELECT session_date FROM court_session WHERE id = ?", String.class, id))
                .isEqualTo("2026-10-01");
    }

    @Test
    void updateRejectsNonPositiveCapacity() throws Exception {
        long id = data.insertSession(courtId, 10);

        mockMvc.perform(put("/api/court-sessions/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("2026-10-01", "18:00", "20:00", 0,
                                "2026-09-25T00:00:00Z", "2026-09-30T23:59:59Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("capacity"));
    }

    @Test
    void updateReturnsNotFoundForUnknownId() throws Exception {
        mockMvc.perform(put("/api/court-sessions/{id}", IntegrationTestData.MISSING_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("2026-10-01", "18:00", "20:00", 10,
                                "2026-09-25T00:00:00Z", "2026-09-30T23:59:59Z")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void deleteReturnsNoContentAndRemovesSession() throws Exception {
        long id = data.insertSession(courtId, 10);

        mockMvc.perform(delete("/api/court-sessions/{id}", id))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(data.countRows("court_session", id)).isZero();
    }

    @Test
    void deleteReturnsNotFoundForUnknownId() throws Exception {
        mockMvc.perform(delete("/api/court-sessions/{id}", IntegrationTestData.MISSING_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void deleteReturnsConflictWhenSessionStillHasBookings() throws Exception {
        long id = data.insertSession(courtId, 10);
        data.insertBooking(id, data.insertMember("Alice"));

        mockMvc.perform(delete("/api/court-sessions/{id}", id))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_IN_USE"));

        assertThat(data.countRows("court_session", id)).isEqualTo(1);
    }

    private long version(long sessionId) {
        return jdbcTemplate.queryForObject("SELECT version FROM court_session WHERE id = ?", Long.class, sessionId);
    }

    private String createBody(long courtId, String startTime, String endTime, int capacity,
                              String openAt, String closeAt) {
        return """
                {"courtId": %d, "sessionDate": "2026-10-01", "startTime": "%s", "endTime": "%s",
                 "capacity": %d, "openAt": "%s", "closeAt": "%s"}
                """.formatted(courtId, startTime, endTime, capacity, openAt, closeAt);
    }

    private String updateBody(String sessionDate, String startTime, String endTime, int capacity,
                              String openAt, String closeAt) {
        return """
                {"sessionDate": "%s", "startTime": "%s", "endTime": "%s", "capacity": %d,
                 "openAt": "%s", "closeAt": "%s"}
                """.formatted(sessionDate, startTime, endTime, capacity, openAt, closeAt);
    }
}
