package errorsupport;

import com.courtservice.common.error.BusinessRuleViolationException;
import com.courtservice.common.error.ResourceNotFoundException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controller that deliberately triggers each exception type GlobalExceptionHandler
 * must translate into a Problem Details response. Lives outside the com.courtservice package
 * tree so it is never picked up by CourtServiceApplication's component scan; it is only
 * registered when TestErrorControllerConfig is explicitly imported by a test.
 */
@RestController
@RequestMapping("/test-errors")
class TestErrorController {

    @GetMapping("/not-found")
    public void notFound() {
        throw new ResourceNotFoundException("Court", 99);
    }

    @GetMapping("/business-rule")
    public void businessRule() {
        throw new BusinessRuleViolationException("Session is already at capacity.");
    }

    @PostMapping("/validate")
    public void validate(@Valid @RequestBody TestErrorRequest request) {
    }

    @PostMapping("/malformed")
    public void malformed(@RequestBody TestErrorRequest request) {
    }

    @GetMapping("/type-mismatch/{id}")
    public void typeMismatch(@PathVariable int id) {
    }

    @GetMapping("/missing-param")
    public void missingParam(@RequestParam String value) {
    }

    @GetMapping("/boom")
    public void boom() {
        throw new RuntimeException("boom");
    }
}