package io.github.pranit.scroll.wrapper;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Window;
import org.springframework.util.Assert;

import java.util.List;
import java.util.function.Function;

/**
 * Represents a scroll-based response containing mapped content and cursor metadata.
 *
 * @param contents     the immutable content of the current scroll window
 * @param nextScrollId the cursor identifying the next scroll position
 * @param prevScrollId the cursor identifying the previous scroll position
 * @param hasNext      whether another scroll window exists
 * @param hasPrevious  whether a previous scroll window exists
 * @param pageSize     the requested page size of the scroll window
 * @param <T>          the type of content elements
 */
public record ScrollResponse<T>(
        List<T> contents,
        @Nullable String nextScrollId,
        @Nullable String prevScrollId,
        boolean hasNext,
        boolean hasPrevious,
        int pageSize
) {

    public ScrollResponse {
        contents = contents == null ? List.of() : List.copyOf(contents);
        Assert.isTrue(pageSize >= 0, "Page size must be >= 0");
        Assert.isTrue(hasPrevious == (prevScrollId != null),
                "Previous scroll ID must be provided exactly when hasPrevious is true");
        Assert.isTrue(hasNext == (nextScrollId != null),
                "Next scroll ID must be provided exactly when hasNext is true");
    }

    public static <T> ScrollResponse<T> from(
            final Window<T> window, final @Nullable String prevScrollId, final @Nullable String nextScrollId, final int pageSize
    ) {
        return from(window, Function.identity(), prevScrollId, nextScrollId, pageSize);
    }

    public static <T> ScrollResponse<T> from(
            final Window<T> window, final @Nullable String prevScrollId, final @Nullable String nextScrollId
    ) {
        Assert.notNull(window, "Window must not be null");
        return from(window, Function.identity(), prevScrollId, nextScrollId, window.size());
    }

    public static <S, T> ScrollResponse<T> from(
            final Window<S> window, final Function<? super S, ? extends T> mapper,
            final @Nullable String prevScrollId, final @Nullable String nextScrollId
    ) {
        Assert.notNull(window, "Window must not be null");
        return from(window, mapper, prevScrollId, nextScrollId, window.size());
    }

    public static <S, T> ScrollResponse<T> from(
            final Window<S> window, final Function<? super S, ? extends T> mapper,
            final @Nullable String prevScrollId, final @Nullable String nextScrollId, final int pageSize
    ) {
        Assert.notNull(window, "Window must not be null");
        Assert.notNull(mapper, "Mapper must not be null");
        final List<T> contents = window.getContent()
                .stream()
                .<T>map(mapper)
                .toList();
        final boolean hasPrevious = prevScrollId != null;
        final boolean hasNext = window.hasNext() && nextScrollId != null;
        return ScrollResponse.<T>builder()
                .contents(contents)
                .nextScrollId(hasNext ? nextScrollId : null)
                .prevScrollId(prevScrollId)
                .hasNext(hasNext)
                .hasPrevious(hasPrevious)
                .pageSize(pageSize)
                .build();
    }

    public boolean isEmpty() {
        return contents.isEmpty();
    }

    public int numberOfElements() {
        return contents.size();
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public static final class Builder<T> {

        private List<T> contents = List.of();
        private @Nullable String nextScrollId;
        private @Nullable String prevScrollId;
        private boolean hasNext;
        private boolean hasPrevious;
        private int pageSize;

        private Builder() {
        }

        public Builder<T> contents(final List<T> contents) {
            this.contents = contents;
            return this;
        }

        public Builder<T> nextScrollId(final @Nullable String nextScrollId) {
            this.nextScrollId = nextScrollId;
            return this;
        }

        public Builder<T> prevScrollId(final @Nullable String prevScrollId) {
            this.prevScrollId = prevScrollId;
            return this;
        }

        public Builder<T> hasNext(final boolean hasNext) {
            this.hasNext = hasNext;
            return this;
        }

        public Builder<T> hasPrevious(final boolean hasPrevious) {
            this.hasPrevious = hasPrevious;
            return this;
        }

        public Builder<T> pageSize(final int pageSize) {
            this.pageSize = pageSize;
            return this;
        }

        public ScrollResponse<T> build() {
            return new ScrollResponse<>(
                    contents,
                    nextScrollId,
                    prevScrollId,
                    hasNext,
                    hasPrevious,
                    pageSize
            );
        }
    }
}
