package io.github.pranit.page.wrapper;

import org.springframework.data.domain.Page;
import org.springframework.util.Assert;

import java.util.List;
import java.util.function.Function;

/**
 * Represents a paginated response containing mapped content and pagination metadata.
 *
 * @param contents      the content of the current page
 * @param currentPage   the zero-based index of the current page
 * @param pageSize      the number of elements per page
 * @param totalElements the total number of elements across all pages
 * @param totalPages    the total number of pages
 * @param isLastPage    whether the current page is the last page
 * @param isFirstPage   whether the current page is the first page
 * @param <T>           the type of content elements
 */
public record PageResponse<T>(
        List<T> contents,
        int currentPage,
        int pageSize,
        long totalElements,
        int totalPages,
        boolean isLastPage,
        boolean isFirstPage
) {

    public PageResponse {
        contents = contents == null ? List.of() : List.copyOf(contents);
        Assert.isTrue(currentPage >= 0, "Current page must be >= 0");
        Assert.isTrue(pageSize >= 0, "Page size must be >= 0");
        Assert.isTrue(totalElements >= 0, "Total elements must be >= 0");
        Assert.isTrue(totalPages >= 0, "Total pages must be >= 0");
    }

    public static <T> PageResponse<T> from(final Page<T> page) {
        return from(page, Function.identity());
    }

    public static <S, T> PageResponse<T> from(
            final Page<S> page, final Function<? super S, ? extends T> mapper
    ) {
        Assert.notNull(page, "Page must not be null");
        Assert.notNull(mapper, "Mapper must not be null");
        final List<T> contents = page.getContent()
                .stream()
                .<T>map(mapper)
                .toList();
        return new PageResponse<>(
                contents,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast(),
                page.isFirst()
        );
    }

    public boolean hasNext() {
        return !isLastPage;
    }

    public boolean hasPrevious() {
        return !isFirstPage;
    }

    public int numberOfElements() {
        return contents.size();
    }

    public boolean isEmpty() {
        return contents.isEmpty();
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public static final class Builder<T> {

        private List<T> contents;
        private int currentPage;
        private int pageSize;
        private long totalElements;
        private int totalPages;
        private boolean isLastPage;
        private boolean isFirstPage;

        private Builder() {
        }

        public Builder<T> contents(final List<T> contents) {
            this.contents = contents;
            return this;
        }

        public Builder<T> currentPage(final int currentPage) {
            this.currentPage = currentPage;
            return this;
        }

        public Builder<T> pageSize(final int pageSize) {
            this.pageSize = pageSize;
            return this;
        }

        public Builder<T> totalElements(final long totalElements) {
            this.totalElements = totalElements;
            return this;
        }

        public Builder<T> totalPages(final int totalPages) {
            this.totalPages = totalPages;
            return this;
        }

        public Builder<T> isLastPage(final boolean isLastPage) {
            this.isLastPage = isLastPage;
            return this;
        }

        public Builder<T> isFirstPage(final boolean isFirstPage) {
            this.isFirstPage = isFirstPage;
            return this;
        }

        public PageResponse<T> build() {
            return new PageResponse<>(contents, currentPage, pageSize, totalElements, totalPages, isLastPage, isFirstPage);
        }
    }
}
