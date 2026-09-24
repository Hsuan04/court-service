package com.courtservice;

import com.courtservice.member.Member;
import com.courtservice.session.CourtSession;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Hibernate statistics are enabled only for this test class, never in src/main configuration,
 * so that the number of SQL statements behind a booking list request can be asserted.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"
})
@AutoConfigureMockMvc
class BookingIntegrationTest {

    /** Session existence check, page data query and page count query. */
    private static final long MAX_STATEMENTS_FOR_BOOKING_PAGE = 3;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private IntegrationTestData data;

    private long sessionId;
    private long otherSessionId;
    private long memberId;
    private long otherMemberId;

    @BeforeEach
    void setUp() {
        data = new IntegrationTestData(jdbcTemplate);
        data.truncateAll();
        long courtId = data.insertCourt("Court A");
        sessionId = data.insertSession(courtId, 10);
        otherSessionId = data.insertSession(courtId, 10);
        memberId = data.insertMember("Alice");
        otherMemberId = data.insertMember("Bob");
    }

    @Test
    void getReturnsBookingWithAssociationIds() throws Exception {
        long bookingId = data.insertBooking(sessionId, memberId);

        mockMvc.perform(get("/api/bookings/{id}", bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bookingId))
                .andExpect(jsonPath("$.courtSessionId").value(sessionId))
                .andExpect(jsonPath("$.memberId").value(memberId))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void getReturnsNotFoundForUnknownBooking() throws Exception {
        mockMvc.perform(get("/api/bookings/{id}", IntegrationTestData.MISSING_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void listByCourtSessionReturnsOnlyThatSessionsBookings() throws Exception {
        long first = data.insertBooking(sessionId, memberId);
        long second = data.insertBooking(sessionId, otherMemberId);
        data.insertBooking(otherSessionId, memberId);

        mockMvc.perform(get("/api/court-sessions/{id}/bookings", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].id").value(first))
                .andExpect(jsonPath("$.content[0].courtSessionId").value(sessionId))
                .andExpect(jsonPath("$.content[1].id").value(second))
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    void listByCourtSessionReturnsEmptyPageForSessionWithoutBookings() throws Exception {
        mockMvc.perform(get("/api/court-sessions/{id}/bookings", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void listByCourtSessionReturnsNotFoundForUnknownSession() throws Exception {
        mockMvc.perform(get("/api/court-sessions/{id}/bookings", IntegrationTestData.MISSING_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void listByMemberReturnsOnlyThatMembersBookings() throws Exception {
        long first = data.insertBooking(sessionId, memberId);
        long second = data.insertBooking(otherSessionId, memberId);
        data.insertBooking(sessionId, otherMemberId);

        mockMvc.perform(get("/api/members/{id}/bookings", memberId).param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(first))
                .andExpect(jsonPath("$.content[0].memberId").value(memberId))
                .andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(2));

        mockMvc.perform(get("/api/members/{id}/bookings", memberId).param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(second));
    }

    @Test
    void listByMemberReturnsNotFoundForUnknownMember() throws Exception {
        mockMvc.perform(get("/api/members/{id}/bookings", IntegrationTestData.MISSING_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    /**
     * Ten bookings from ten distinct members are listed with page size 5, which forces Spring
     * Data to run the count query. Without N+1 the request needs a fixed number of statements
     * (existence check, data query, count query) regardless of how many rows the page holds,
     * and never loads a CourtSession or Member.
     */
    @Test
    void listByCourtSessionDoesNotTriggerNPlusOneQueries() throws Exception {
        // given
        int bookings = 10;
        for (int i = 0; i < bookings; i++) {
            data.insertBooking(sessionId, data.insertMember("Member " + i));
        }
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        // when
        mockMvc.perform(get("/api/court-sessions/{id}/bookings", sessionId).param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(5)))
                .andExpect(jsonPath("$.page.totalElements").value(bookings));

        // then
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(MAX_STATEMENTS_FOR_BOOKING_PAGE);
        assertThat(loadsAndFetches(statistics, CourtSession.class)).isZero();
        assertThat(loadsAndFetches(statistics, Member.class)).isZero();
    }

    private long loadsAndFetches(Statistics statistics, Class<?> entityType) {
        var entityStatistics = statistics.getEntityStatistics(entityType.getName());
        return entityStatistics.getLoadCount() + entityStatistics.getFetchCount();
    }
}
