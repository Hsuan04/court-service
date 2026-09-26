package com.courtservice.common.logging;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the application-relative request path used for logging and exclusion matching.
 */
final class RequestPaths {

    private RequestPaths() {
    }

    static String of(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
