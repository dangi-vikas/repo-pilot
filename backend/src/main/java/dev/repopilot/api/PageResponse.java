package dev.repopilot.api;

import java.util.List;
import java.util.function.Function;

/**
 * Standard pagination envelope. Every list endpoint in RepoPilot returns this
 * shape so clients can page uniformly and detect truncation.
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalItems,
        int totalPages,
        boolean hasNext
) {

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalItems) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalItems / size);
        return new PageResponse<>(items, page, size, totalItems, totalPages, page + 1 < totalPages);
    }

    /** Maps the item type while preserving pagination metadata. */
    public <R> PageResponse<R> map(Function<T, R> mapper) {
        return new PageResponse<>(items.stream().map(mapper).toList(),
                page, size, totalItems, totalPages, hasNext);
    }

    /** A single, unpaginated page. Used for bounded result sets that are never large. */
    public static <T> PageResponse<T> single(List<T> items) {
        return new PageResponse<>(items, 0, Math.max(items.size(), 1), items.size(), 1, false);
    }
}
