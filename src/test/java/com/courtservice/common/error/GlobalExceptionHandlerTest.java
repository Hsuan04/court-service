package com.courtservice.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new ProblemDetailFactory());
    private final WebRequest request = new ServletWebRequest(new MockHttpServletRequest("GET", "/api/court-sessions"));

    @Test
    void dataIntegrityViolationMapsToResourceInUse() {
        ResponseEntity<Object> response = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("uq_booking_session_member"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(properties(response)).containsEntry("code", "RESOURCE_IN_USE");
    }

    @Test
    void optimisticLockingFailureMapsToConcurrentModification() {
        ResponseEntity<Object> response = handler.handleOptimisticLockingFailure(
                new ObjectOptimisticLockingFailureException("CourtSession", 1L), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(properties(response)).containsEntry("code", "CONCURRENT_MODIFICATION");
    }

    @Test
    void unexpectedExceptionMapsToInternalErrorWithoutLeakingMessage() {
        ResponseEntity<Object> response = handler.handleUnexpectedException(new RuntimeException("secret detail"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        ProblemDetail body = (ProblemDetail) response.getBody();
        assertThat(body.getDetail()).doesNotContain("secret detail");
    }

    private java.util.Map<String, Object> properties(ResponseEntity<Object> response) {
        return ((ProblemDetail) response.getBody()).getProperties();
    }
}