package loggingsupport;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Test-only controller that echoes a payload containing sensitive fields at several depths, so
 * that request logging can be checked for masking on both the request and the response. Lives
 * outside the com.courtservice package tree so it is only registered when
 * TestLoggingControllerConfig is explicitly imported.
 */
@RestController
@RequestMapping("/test-logging")
class TestLoggingController {

    record Credentials(String token, String label) {
    }

    record SignUpRequest(String username, String password, Credentials credentials, List<Credentials> history) {
    }

    @PostMapping("/sign-up")
    public SignUpRequest signUp(@RequestBody SignUpRequest request) {
        return request;
    }
}
