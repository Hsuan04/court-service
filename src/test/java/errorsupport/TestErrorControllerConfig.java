package errorsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link TestErrorController} only for the tests that import this configuration,
 * so the test-only endpoints never appear in other tests' contexts or generated OpenAPI docs.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestErrorControllerConfig {

    @Bean
    TestErrorController testErrorController() {
        return new TestErrorController();
    }
}