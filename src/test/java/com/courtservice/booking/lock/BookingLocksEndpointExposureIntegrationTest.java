package com.courtservice.booking.lock;

import com.courtservice.RedisTestData;
import com.courtservice.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.context.NestedTestConfiguration.EnclosingConfiguration.OVERRIDE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checks when {@code /actuator/bookinglocks} is reachable over HTTP: only when the endpoint is
 * listed in {@code management.endpoints.web.exposure.include} and the distributed-lock mode created
 * its bean. Each case needs different properties, so each runs in its own context.
 */
class BookingLocksEndpointExposureIntegrationTest {

    private static final String ENDPOINT = "/actuator/bookinglocks";
    private static final String DISTRIBUTED_LOCK = "app.booking.concurrency-mode=distributed-lock";
    private static final String PESSIMISTIC_LOCK = "app.booking.concurrency-mode=pessimistic-lock";
    private static final String EXPOSED = "management.endpoints.web.exposure.include=health,bookinglocks";
    private static final String NOT_EXPOSED = "management.endpoints.web.exposure.include=health";

    @Nested
    @NestedTestConfiguration(OVERRIDE)
    @Import(TestcontainersConfiguration.class)
    @SpringBootTest(properties = {DISTRIBUTED_LOCK, EXPOSED})
    @AutoConfigureMockMvc
    class ExposedInDistributedLockMode {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private RedissonClient redissonClient;

        @BeforeEach
        void setUp() {
            new RedisTestData(redissonClient).flushAll();
        }

        @Test
        void returnsTheLockList() throws Exception {
            mockMvc.perform(get(ENDPOINT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
        }
    }

    @Nested
    @NestedTestConfiguration(OVERRIDE)
    @Import(TestcontainersConfiguration.class)
    @SpringBootTest(properties = {DISTRIBUTED_LOCK, NOT_EXPOSED})
    @AutoConfigureMockMvc
    class NotExposed {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void isNotFound() throws Exception {
            mockMvc.perform(get(ENDPOINT)).andExpect(status().isNotFound());
        }
    }

    @Nested
    @NestedTestConfiguration(OVERRIDE)
    @Import(TestcontainersConfiguration.class)
    @SpringBootTest(properties = {PESSIMISTIC_LOCK, EXPOSED})
    @AutoConfigureMockMvc
    class ExposedButAnotherMode {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void isNotFoundBecauseTheEndpointBeanIsNotCreated() throws Exception {
            mockMvc.perform(get(ENDPOINT)).andExpect(status().isNotFound());
        }
    }
}
