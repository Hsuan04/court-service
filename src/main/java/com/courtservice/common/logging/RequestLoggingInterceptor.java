package com.courtservice.common.logging;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

/**
 * Writes the INFO summary of every controller call: one line on entry and one on completion.
 *
 * <p>The completion line is written in {@link #afterCompletion}, after exception handling has
 * run, because only then is the final status known, including a status set through
 * {@code ResponseEntity} and a status produced by {@code GlobalExceptionHandler} (for example
 * 404 or 409). An aspect around the controller method cannot see that status. The companion
 * {@link RequestLoggingAspect} only captures the DEBUG payloads, which require the resolved
 * method arguments and the return value.
 *
 * <p>Stack traces are never logged here; {@code GlobalExceptionHandler} owns error logging.
 */
@Slf4j
public class RequestLoggingInterceptor implements HandlerInterceptor {

    static final String START_NANOS_ATTRIBUTE = RequestLoggingInterceptor.class.getName() + ".startNanos";
    static final String RESULT_ATTRIBUTE = RequestLoggingInterceptor.class.getName() + ".result";

    private final RequestLoggingPathMatcher pathMatcher;

    /**
     * @param pathMatcher decides which paths are excluded from logging
     */
    public RequestLoggingInterceptor(RequestLoggingPathMatcher pathMatcher) {
        this.pathMatcher = pathMatcher;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!isLogged(request, handler)) {
            return true;
        }
        request.setAttribute(START_NANOS_ATTRIBUTE, System.nanoTime());
        log.info("--> {} {} {}", request.getMethod(), RequestPaths.of(request), describe((HandlerMethod) handler));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        if (!(request.getAttribute(START_NANOS_ATTRIBUTE) instanceof Long startNanos)) {
            return;
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        Throwable failure = ex != null ? ex : (Throwable) request.getAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE);
        String method = request.getMethod();
        String path = RequestPaths.of(request);
        String target = describe((HandlerMethod) handler);
        if (failure == null) {
            log.info("<-- {} {} {} {} {}ms", method, path, response.getStatus(), target, elapsedMs);
        } else {
            log.info("<-- {} {} {} {} {}ms exception={}", method, path, response.getStatus(), target, elapsedMs,
                    failure.getClass().getSimpleName());
        }
        if (log.isDebugEnabled() && request.getAttribute(RESULT_ATTRIBUTE) instanceof String result) {
            log.debug("    result: {}", result);
        }
    }

    private boolean isLogged(HttpServletRequest request, Object handler) {
        return handler instanceof HandlerMethod && !pathMatcher.isExcluded(RequestPaths.of(request));
    }

    private static String describe(HandlerMethod handlerMethod) {
        return handlerMethod.getBeanType().getSimpleName() + "." + handlerMethod.getMethod().getName();
    }
}
