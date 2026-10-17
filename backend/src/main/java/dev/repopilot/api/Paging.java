package dev.repopilot.api;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Bounded page construction for query parameters.
 *
 * <p>An unbounded {@code size} is a denial-of-service vector: a client asking for
 * {@code size=100000000} would make the server attempt to materialise the whole
 * table. Every list endpoint therefore clamps through here.
 */
public final class Paging {

    /** Default page size when the client does not specify one. */
    public static final int DEFAULT_SIZE = 25;
    /** Hard ceiling on a single page, regardless of the requested size. */
    public static final int MAX_SIZE = 200;

    private Paging() {
    }

    public static Pageable of(Integer page, Integer size, Sort sort) {
        int resolvedPage = page == null || page < 0 ? 0 : page;
        int resolvedSize = size == null || size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return PageRequest.of(resolvedPage, resolvedSize, sort);
    }
}
