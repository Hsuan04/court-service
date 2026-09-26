package com.courtservice.common.logging;

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

/**
 * Decides whether a request path is excluded from request logging, using the same
 * {@link PathPattern} syntax as Spring MVC mappings (for example {@code /actuator/**}).
 */
public class RequestLoggingPathMatcher {

    private final List<PathPattern> excludedPatterns;

    /**
     * @param excludedPaths the patterns to exclude
     */
    public RequestLoggingPathMatcher(List<String> excludedPaths) {
        this.excludedPatterns = excludedPaths.stream()
                .map(PathPatternParser.defaultInstance::parse)
                .toList();
    }

    /**
     * Returns whether the given path matches any excluded pattern.
     *
     * @param path the request path, without the context path
     * @return {@code true} if the path must not be logged
     */
    public boolean isExcluded(String path) {
        PathContainer container = PathContainer.parsePath(path);
        return excludedPatterns.stream().anyMatch(pattern -> pattern.matches(container));
    }
}
