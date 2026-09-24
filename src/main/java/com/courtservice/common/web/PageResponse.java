package com.courtservice.common.web;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Standard envelope for paginated list responses.
 *
 * @param content the page's items
 * @param page    pagination metadata
 * @param <T>     the item type
 */
public record PageResponse<T>(List<T> content, PageMeta page) {

    /**
     * Builds a {@link PageResponse} from a Spring Data {@link Page}.
     *
     * @param page the source page
     * @param <T>  the item type
     * @return an equivalent {@link PageResponse}
     */
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(),
                new PageMeta(page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages()));
    }

    /**
     * Pagination metadata for a {@link PageResponse}.
     *
     * @param number         zero-based page index
     * @param size           requested page size
     * @param totalElements  total number of elements across all pages
     * @param totalPages     total number of pages
     */
    public record PageMeta(int number, int size, long totalElements, int totalPages) {
    }
}