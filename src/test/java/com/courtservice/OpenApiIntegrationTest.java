package com.courtservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocsIncludeHealthEndpointAndTag() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths./api/health").exists())
                .andExpect(jsonPath("$.tags[?(@.name == 'Health')]").exists())
                .andExpect(jsonPath("$.components.schemas.ProblemDetail").exists());
    }

    @Test
    void apiDocsIncludeCrudAndBookingEndpointsWithTags() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths./api/courts.post").exists())
                .andExpect(jsonPath("$.paths./api/courts/{id}.delete").exists())
                .andExpect(jsonPath("$.paths./api/members.get").exists())
                .andExpect(jsonPath("$.paths./api/members/{id}.put").exists())
                .andExpect(jsonPath("$.paths./api/court-sessions.post").exists())
                .andExpect(jsonPath("$.paths./api/court-sessions/{id}.get").exists())
                .andExpect(jsonPath("$.paths./api/bookings/{id}.get").exists())
                .andExpect(jsonPath("$.paths./api/court-sessions/{id}/bookings.get").exists())
                .andExpect(jsonPath("$.paths./api/members/{id}/bookings.get").exists())
                .andExpect(jsonPath("$.tags[?(@.name == 'Courts')]").exists())
                .andExpect(jsonPath("$.tags[?(@.name == 'Members')]").exists())
                .andExpect(jsonPath("$.tags[?(@.name == 'Court Sessions')]").exists())
                .andExpect(jsonPath("$.tags[?(@.name == 'Bookings')]").exists());
    }

    @Test
    void apiDocsExposePaginationParametersOnListEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths./api/courts.get.parameters[*].name",
                        hasItems("page", "size", "sort")))
                .andExpect(jsonPath("$.paths./api/court-sessions/{id}/bookings.get.parameters[*].name",
                        hasItems("id", "page", "size", "sort")));
    }

    @Test
    void apiDocsMarkBookedCountReadOnlyAndErrorsAsProblemDetails() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.CourtSessionResponse.properties.bookedCount.readOnly")
                        .value(true))
                .andExpect(jsonPath("$.components.schemas.CourtSessionCreateRequest.properties.bookedCount")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CourtSessionUpdateRequest.properties.bookedCount")
                        .doesNotExist())
                .andExpect(jsonPath("$.paths./api/courts/{id}.get.responses.404.content"
                        + "['application/problem+json'].schema.$ref")
                        .value("#/components/schemas/ProblemDetail"));
    }
}