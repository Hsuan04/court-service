package loggingsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link TestLoggingController} only for the tests that import this configuration.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestLoggingControllerConfig {

    @Bean
    TestLoggingController testLoggingController() {
        return new TestLoggingController();
    }
}
