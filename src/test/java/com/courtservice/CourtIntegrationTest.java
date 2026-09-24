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
class CourtIntegrationTest {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

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
        mockMvc.perform(post("/api/courts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Court A", "address": "Taipei"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/courts/1")))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Court A"))
                .andExpect(jsonPath("$.address").value("Taipei"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());

        assertThat(data.countRows("court", 1)).isEqualTo(1);
    }

    @Test
    void createAcceptsMissingAddress() throws Exception {
        mockMvc.perform(post("/api/courts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Court A"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.address").doesNotExist());
    }

    @Test
    void createRejectsBlankName() throws Exception {
        mockMvc.perform(post("/api/courts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": " "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void createRejectsTooLongNameAndAddress() throws Exception {
        String body = """
                {"name": "%s", "address": "%s"}
                """.formatted("n".repeat(101), "a".repeat(256));

        mockMvc.perform(post("/api/courts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(2)));
    }

    @Test
    void getReturnsCourt() throws Exception {
        long id = data.insertCourt("Court A");

        mockMvc.perform(get("/api/courts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Court A"))
                .andExpect(jsonPath("$.address").value("Taipei"));
    }

    @Test
    void getReturnsNotFoundForUnknownId() throws Exception {
        mockMvc.perform(get("/api/courts/{id}", IntegrationTestData.MISSING_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void listWithoutParametersUsesDefaultPageSizeSortedByIdAscending() throws Exception {
        long first = data.insertCourt("Court A");
        long second = data.insertCourt("Court B");

        mockMvc.perform(get("/api/courts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].id").value(first))
                .andExpect(jsonPath("$.content[1].id").value(second))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(DEFAULT_PAGE_SIZE))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.page.totalPages").value(1));
    }

    @Test
    void listWithPageAndSizeReturnsRequestedPage() throws Exception {
        data.insertCourt("Court A");
        data.insertCourt("Court B");
        long third = data.insertCourt("Court C");

        mockMvc.perform(get("/api/courts").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(third))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2));
    }

    @Test
    void listCapsPageSizeAtMaximum() throws Exception {
        for (int i = 0; i <= MAX_PAGE_SIZE; i++) {
            data.insertCourt("Court " + i);
        }

        mockMvc.perform(get("/api/courts").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(MAX_PAGE_SIZE)))
                .andExpect(jsonPath("$.page.size").value(MAX_PAGE_SIZE))
                .andExpect(jsonPath("$.page.totalElements").value(MAX_PAGE_SIZE + 1));
    }

    @Test
    void updateReplacesCourt() throws Exception {
        long id = data.insertCourt("Court A");

        mockMvc.perform(put("/api/courts/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Court Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Court Z"))
                .andExpect(jsonPath("$.address").doesNotExist());

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM court WHERE id = ?", String.class, id))
                .isEqualTo("Court Z");
        assertThat(jdbcTemplate.queryForObject("SELECT address FROM court WHERE id = ?", String.class, id))
                .isNull();
    }

    @Test
    void updateRejectsBlankName() throws Exception {
        long id = data.insertCourt("Court A");

        mockMvc.perform(put("/api/courts/{id}", id)
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
        mockMvc.perform(put("/api/courts/{id}", IntegrationTestData.MISSING_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Court Z"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void deleteReturnsNoContentAndRemovesCourt() throws Exception {
        long id = data.insertCourt("Court A");

        mockMvc.perform(delete("/api/courts/{id}", id))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(data.countRows("court", id)).isZero();
    }

    @Test
    void deleteReturnsNotFoundForUnknownId() throws Exception {
        mockMvc.perform(delete("/api/courts/{id}", IntegrationTestData.MISSING_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void deleteReturnsConflictWhenCourtStillHasSessions() throws Exception {
        long id = data.insertCourt("Court A");
        data.insertSession(id, 10);

        mockMvc.perform(delete("/api/courts/{id}", id))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_IN_USE"));

        assertThat(data.countRows("court", id)).isEqualTo(1);
    }
}
