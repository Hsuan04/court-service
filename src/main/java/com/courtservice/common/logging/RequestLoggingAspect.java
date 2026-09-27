package com.courtservice.common.logging;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.domain.Pageable;
import org.springframework.ui.Model;
import org.springframework.validation.Errors;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.annotation.Annotation;
import java.security.Principal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Logs the payloads of every public {@code @RestController} method at DEBUG: path variables,
 * query parameters, the {@link Pageable} and the request body on the way in, and the return
 * value on the way out.
 *
 * <p>Payloads are serialized only when DEBUG is enabled, so the INFO-only profiles pay nothing
 * beyond one level check. The return value is handed to {@link RequestLoggingInterceptor}
 * through a request attribute, so that it appears after the completion line that carries the
 * final status. Framework objects such as the servlet request, {@code BindingResult} or the
 * {@code Model} are never serialized.
 */
@Slf4j
@Aspect
public class RequestLoggingAspect {

    private static final List<Class<?>> FRAMEWORK_TYPES = List.of(ServletRequest.class, ServletResponse.class,
            HttpSession.class, Errors.class, Principal.class, Model.class);

    private final PayloadFormatter payloadFormatter;
    private final RequestLoggingPathMatcher pathMatcher;

    /**
     * @param payloadFormatter formats payloads safely for logging
     * @param pathMatcher      decides which paths are excluded from logging
     */
    public RequestLoggingAspect(PayloadFormatter payloadFormatter, RequestLoggingPathMatcher pathMatcher) {
        this.payloadFormatter = payloadFormatter;
        this.pathMatcher = pathMatcher;
    }

    /**
     * Logs the method arguments before and captures the return value after a controller call.
     *
     * @param joinPoint the intercepted controller method
     * @return the controller's return value, unchanged
     * @throws Throwable whatever the controller throws, unchanged
     */
    @Around("@within(org.springframework.web.bind.annotation.RestController) && execution(public * *(..))")
    public Object logPayloads(ProceedingJoinPoint joinPoint) throws Throwable {
        HttpServletRequest request = currentRequest();
        if (!log.isDebugEnabled() || request == null || pathMatcher.isExcluded(RequestPaths.of(request))) {
            return joinPoint.proceed();
        }
        log.debug("    args: {}", describeArguments(joinPoint, request));
        Object result = joinPoint.proceed();
        request.setAttribute(RequestLoggingInterceptor.RESULT_ATTRIBUTE, payloadFormatter.format(result));
        return result;
    }

    private String describeArguments(ProceedingJoinPoint joinPoint, HttpServletRequest request) {
        Object pathVariables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        Map<String, List<String>> query = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> query.put(name, Arrays.asList(values)));

        StringBuilder description = new StringBuilder()
                .append("path=").append(payloadFormatter.format(pathVariables == null ? Map.of() : pathVariables))
                .append(", query=").append(payloadFormatter.format(query));

        Object[] args = joinPoint.getArgs();
        Annotation[][] parameterAnnotations = ((MethodSignature) joinPoint.getSignature()).getMethod()
                .getParameterAnnotations();
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg instanceof Pageable pageable) {
                description.append(", pageable={page=").append(pageable.getPageNumber())
                        .append(", size=").append(pageable.getPageSize())
                        .append(", sort=").append(pageable.getSort()).append('}');
            } else if (isRequestBody(parameterAnnotations[i]) && !isFrameworkObject(arg)) {
                description.append(", body=").append(payloadFormatter.format(arg));
            }
        }
        return description.toString();
    }

    private static boolean isRequestBody(Annotation[] annotations) {
        return Arrays.stream(annotations).anyMatch(RequestBody.class::isInstance);
    }

    private static boolean isFrameworkObject(Object arg) {
        return arg != null && FRAMEWORK_TYPES.stream().anyMatch(type -> type.isInstance(arg));
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servletAttributes ? servletAttributes.getRequest() : null;
    }
}
