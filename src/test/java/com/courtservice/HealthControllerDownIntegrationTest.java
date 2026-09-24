package com.courtservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class HealthControllerDownIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthCheckReturnsServiceUnavailableWhenAComponentIsDown() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
    }

    /**
     * Contributes a failing indicator so the real Actuator HealthEndpoint aggregates an
     * overall DOWN status. HealthDescriptor, the endpoint's return type, is sealed and
     * cannot be mocked, so simulating a failing component is done through a real
     * HealthIndicator instead of stubbing HealthEndpoint directly.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FailingHealthIndicatorConfig {

        @Bean
        HealthIndicator failingHealthIndicator() {
            return () -> Health.down().withDetail("reason", "simulated failure for testing").build();
        }
    }
}