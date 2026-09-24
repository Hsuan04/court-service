package com.courtservice.common.web;

/**
 * Shared defaults for paginated list endpoints.
 *
 * <p>{@code @PageableDefault} carries its own {@code size} default of 10, which takes precedence
 * over {@code spring.data.web.pageable.default-page-size}. Controllers therefore pass
 * {@link #DEFAULT_PAGE_SIZE} explicitly so that the documented default of 20 applies whenever a
 * default sort is also declared. The upper bound is enforced globally through
 * {@code spring.data.web.pageable.max-page-size}.
 */
public final class PaginationDefaults {

    /** Page size used when the client does not send {@code size}. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** Sort property used when the client does not send {@code sort}. */
    public static final String DEFAULT_SORT_PROPERTY = "id";

    private PaginationDefaults() {
    }
}
