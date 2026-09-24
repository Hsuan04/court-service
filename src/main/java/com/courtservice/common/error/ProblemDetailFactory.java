package com.courtservice.common.error;

import com.courtservice.common.web.TraceIdFilter;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Builds {@link ProblemDetail} responses with the extension fields required by the API
 * conventions ({@code code}, {@code timestamp}, {@code traceId} and, for validation
 * failures, {@code errors}), so this assembly logic exists in exactly one place.
 */
@Component
public class ProblemDetailFactory {

    /**
     * Builds a Problem Details response for the given error code and detail message.
     *
     * @param errorCode the error code driving status, title and type URI
     * @param detail    the human-readable detail message
     * @param request   the current web request, used to populate {@code instance}
     * @return a fully populated {@link ProblemDetail}
     */
    public ProblemDetail create(ErrorCode errorCode, String detail, WebRequest request) {
        return create(errorCode, detail, request, null);
    }

    /**
     * Builds a Problem Details response that additionally lists field-level validation
     * failures.
     *
     * @param errorCode the error code driving status, title and type URI
     * @param detail    the human-readable detail message
     * @param request   the current web request, used to populate {@code instance}
     * @param errors    the field-level validation failures, or {@code null} if none apply
     * @return a fully populated {@link ProblemDetail}
     */
    public ProblemDetail create(ErrorCode errorCode, String detail, WebRequest request, List<ValidationError> errors) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(errorCode.getHttpStatus(), detail);
        problemDetail.setType(errorCode.getType());
        problemDetail.setTitle(errorCode.getTitle());
        problemDetail.setInstance(resolveInstance(request));
        problemDetail.setProperty("code", errorCode.name());
        problemDetail.setProperty("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        problemDetail.setProperty("traceId", MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY));
        if (errors != null && !errors.isEmpty()) {
            problemDetail.setProperty("errors", errors);
        }
        return problemDetail;
    }

    private URI resolveInstance(WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest) {
            return URI.create(servletWebRequest.getRequest().getRequestURI());
        }
        return null;
    }
}