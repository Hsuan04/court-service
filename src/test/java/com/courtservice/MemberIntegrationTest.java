package com.courtservice;

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
class MemberIntegrationTest {

    private static final int DEFAULT_PAGE_SIZE = 20;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private IntegrationTestData data;

    @BeforeEach
    void setUp() {
        data = new IntegrationTestData(jdbcTemplate);
        data.truncateAll();
    }

    @Test
    void createReturnsCreatedWithLocationAndBody() throws Exception {
        mockMvc.perform(post("/api/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Alice"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/members/1")))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());

        assertThat(data.countRows("member", 1)).isEqualTo(1);
    }

    @Test
    void createRejectsMissingName() throws Exception {
        mockMvc.perform(post("/api/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void createRejectsTooLongName() throws Exception {
        mockMvc.perform(post("/api/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s"}
                                """.formatted("n".repeat(101))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void getReturnsMember() throws Exception {
        long id = data.insertMember("Alice");

        mockMvc.perform(get("/api/members/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Alice"));
    }

    @Test
    void getReturnsNotFoundForUnknownId() throws Exception {
        mockMvc.perform(get("/api/members/{id}", IntegrationTestData.MISSING_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void listWithoutParametersUsesDefaultPageSizeSortedByIdAscending() throws Exception {
        long first = data.insertMember("Alice");
        long second = data.insertMember("Bob");

        mockMvc.perform(get("/api/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].id").value(first))
                .andExpect(jsonPath("$.content[1].id").value(second))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(DEFAULT_PAGE_SIZE))
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    void listWithPageAndSizeReturnsRequestedPage() throws Exception {
        data.insertMember("Alice");
        long second = data.insertMember("Bob");
        data.insertMember("Carol");

        mockMvc.perform(get("/api/members").param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(second))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(3));
    }

    @Test
    void updateRenamesMember() throws Exception {
        long id = data.insertMember("Alice");

        mockMvc.perform(put("/api/members/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Alicia"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Alicia"));

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM member WHERE id = ?", String.class, id))
                .isEqualTo("Alicia");
    }

    @Test
    void updateRejectsBlankName() throws Exception {
        long id = data.insertMember("Alice");

        mockMvc.perform(put("/api/members/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void updateReturnsNotFoundForUnknownId() throws Exception {
        mockMvc.perform(put("/api/members/{id}", IntegrationTestData.MISSING_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Alicia"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void deleteReturnsNoContentAndRemovesMember() throws Exception {
        long id = data.insertMember("Alice");

        mockMvc.perform(delete("/api/members/{id}", id))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(data.countRows("member", id)).isZero();
    }

    @Test
    void deleteReturnsNotFoundForUnknownId() throws Exception {
        mockMvc.perform(delete("/api/members/{id}", IntegrationTestData.MISSING_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void deleteReturnsConflictWhenMemberStillHasBookings() throws Exception {
        long memberId = data.insertMember("Alice");
        long sessionId = data.insertSession(data.insertCourt("Court A"), 10);
        data.insertBooking(sessionId, memberId);

        mockMvc.perform(delete("/api/members/{id}", memberId))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_IN_USE"));

        assertThat(data.countRows("member", memberId)).isEqualTo(1);
    }
}
