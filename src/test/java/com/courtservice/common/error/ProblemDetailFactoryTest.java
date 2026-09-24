package com.courtservice.common.error;

import com.courtservice.common.web.TraceIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemDetailFactoryTest {

    private final ProblemDetailFactory factory = new ProblemDetailFactory();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void createPopulatesAllSharedExtensionFields() {
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-xyz");
        WebRequest request = new ServletWebRequest(new MockHttpServletRequest("GET", "/api/courts/99"));

        ProblemDetail problemDetail = factory.create(ErrorCode.RESOURCE_NOT_FOUND,
                "Court with id 99 was not found.", request);

        assertThat(problemDetail.getStatus()).isEqualTo(404);
        assertThat(problemDetail.getTitle()).isEqualTo("Resource Not Found");
        assertThat(problemDetail.getDetail()).isEqualTo("Court with id 99 was not found.");
        assertThat(problemDetail.getType())
                .isEqualTo(URI.create("https://court-service.example/problems/resource-not-found"));
        assertThat(problemDetail.getInstance()).isEqualTo(URI.create("/api/courts/99"));
        assertThat(problemDetail.getProperties()).containsEntry("code", "RESOURCE_NOT_FOUND");
        assertThat(problemDetail.getProperties()).containsEntry("traceId", "trace-xyz");
        assertThat((String) problemDetail.getProperties().get("timestamp")).isNotBlank();
    }

    @Test
    void errorsPropertyIsOmittedWhenNoValidationErrorsAreGiven() {
        WebRequest request = new ServletWebRequest(new MockHttpServletRequest("POST", "/api/courts"));

        ProblemDetail withoutErrors = factory.create(ErrorCode.VALIDATION_FAILED, "detail", request, List.of());
        ProblemDetail withoutErrorsArg = factory.create(ErrorCode.VALIDATION_FAILED, "detail", request);

        assertThat(withoutErrors.getProperties()).doesNotContainKey("errors");
        assertThat(withoutErrorsArg.getProperties()).doesNotContainKey("errors");
    }

    @Test
    void errorsPropertyListsEachValidationFailureWhenGiven() {
        WebRequest request = new ServletWebRequest(new MockHttpServletRequest("POST", "/api/courts"));
        List<ValidationError> errors = List.of(new ValidationError("name", "must not be blank", ""));

        ProblemDetail problemDetail = factory.create(ErrorCode.VALIDATION_FAILED, "detail", request, errors);

        assertThat(problemDetail.getProperties()).containsEntry("errors", errors);
    }
}