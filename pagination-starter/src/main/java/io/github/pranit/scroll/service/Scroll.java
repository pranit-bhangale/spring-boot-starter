package io.github.pranit.scroll.service;

import io.github.pranit.scroll.exception.InvalidScrollingException;
import io.github.pranit.scroll.wrapper.ScrollResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Provides generic, bidirectional keyset scrolling and content mapping utilities for Spring Data JPA.
 */
public final class Scroll {

    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MIN_PAGE_SIZE = 1;
    public static final int MAX_PAGE_SIZE = 100;

    private Scroll() {
        throw new UnsupportedOperationException("Scroll is a utility class and cannot be instantiated");
    }

    /**
     * Executes bidirectional keyset scrolling using primitive parameters without mapping contents.
     */
    public static <E> ScrollResponse<E> scroll(
            final JpaSpecificationExecutor<E> repository,
            final @Nullable Specification<E> specification,
            final @Nullable String scrollId,
            final int pageSize,
            final String sortBy,
            final @Nullable String sortDirection,
            final @Nullable String scrollDirection,
            final @Nullable Collection<String> allowedSortProperties
    ) {
        return scroll(repository, specification, scrollId, pageSize, sortBy, sortDirection, scrollDirection, allowedSortProperties, Function.identity());
    }

    /**
     * Executes bidirectional keyset scrolling using primitive parameters and maps contents to DTOs.
     */
    public static <E, D> ScrollResponse<D> scroll(
            final JpaSpecificationExecutor<E> repository, final @Nullable Specification<E> specification,
            final @Nullable String scrollId, final int pageSize,
            final String sortBy, final @Nullable String sortDirection,
            final @Nullable String scrollDirection, final @Nullable Collection<String> allowedSortProperties,
            final Function<? super E, ? extends D> mapper
    ) {
        Assert.notNull(repository, "Repository must not be null");
        Assert.notNull(mapper, "Mapper must not be null");
        validatePageSize(pageSize);
        final Map<String, String> allowedProperties =
                normalizeAllowedSortProperties(allowedSortProperties);
        final String canonicalSortBy =
                validateSortBy(sortBy, allowedProperties);
        final Sort.Direction requestedSortDirection =
                parseSortDirection(sortDirection);
        final Sort sort = Sort.by(requestedSortDirection, canonicalSortBy);
        final ScrollPositionCodec.ScrollDirection requestedScrollDirection =
                parseScrollDirection(scrollDirection);
        return executeScroll(repository, specification, scrollId, pageSize, sort, requestedScrollDirection, mapper);
    }

    private static void validatePageSize(final int size) {
        Assert.isTrue(size >= MIN_PAGE_SIZE && size <= MAX_PAGE_SIZE,
                () -> "Page size must be between " + MIN_PAGE_SIZE + " and " + MAX_PAGE_SIZE);
    }

    private static Map<String, String> normalizeAllowedSortProperties(final @Nullable Collection<String> allowedSortProperties) {
        if (allowedSortProperties == null || allowedSortProperties.isEmpty()) {
            return Collections.emptyMap();
        }
        final Map<String, String> normalized = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (final String property : allowedSortProperties) {
            Assert.hasText(property, "Allowed sort properties must not be null or blank");
            final String trimmed = property.trim();
            normalized.put(trimmed, trimmed);
        }
        return Collections.unmodifiableMap(normalized);
    }

    private static String validateSortBy(final @Nullable String sortBy, final Map<String, String> allowedSortProperties) {
        Assert.hasText(sortBy, "Sort property must not be null or blank");
        final String trimmed = sortBy.trim();
        if (allowedSortProperties.isEmpty()) {
            throw new IllegalArgumentException("Sorting is not supported for this query");
        }
        final String canonical = allowedSortProperties.get(trimmed);
        Assert.isTrue(canonical != null, () -> "Unsupported sort property: " + trimmed);
        return canonical;
    }

    private static Sort.Direction parseSortDirection(final @Nullable String sortDirection) {
        if (sortDirection == null || sortDirection.isBlank()) return Sort.Direction.ASC;
        return switch (sortDirection.trim().toLowerCase(Locale.ROOT)) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw new IllegalArgumentException("Sort direction must be 'asc' or 'desc'");
        };
    }

    private static ScrollPositionCodec.ScrollDirection parseScrollDirection(final @Nullable String scrollDirection) {
        if (scrollDirection == null || scrollDirection.isBlank()) {
            return ScrollPositionCodec.ScrollDirection.FORWARD;
        }
        try {
            return ScrollPositionCodec.ScrollDirection.from(scrollDirection);
        } catch (IllegalArgumentException e) {
            throw new InvalidScrollingException(e.getMessage(), e);
        }
    }

    private static <E, D> ScrollResponse<D> executeScroll(
            final JpaSpecificationExecutor<E> repository, final @Nullable Specification<E> specification,
            final @Nullable String scrollId, final int pageSize,
            final Sort sort, final ScrollPositionCodec.ScrollDirection requestedDirection,
            final Function<? super E, ? extends D> mapper
    ) {
        final boolean initialRequest = scrollId == null || scrollId.isBlank();
        if (initialRequest && requestedDirection == ScrollPositionCodec.ScrollDirection.BACKWARD) {
            throw new InvalidScrollingException("Backward scrolling requires a scrollId");
        }
        final ScrollPositionCodec.DecodedScrollPosition decoded =
                ScrollPositionCodec.decode(scrollId);
        if (!initialRequest && decoded.direction() != requestedDirection) {
            throw new InvalidScrollingException("scrollId direction does not match scrollDirection");
        }
        final ScrollPosition position = decoded.position();
        final Specification<E> targetSpecification = specification != null
                ? specification : Specification.unrestricted();
        final Window<E> window = repository
                .findBy(targetSpecification, query -> query
                        .limit(pageSize)
                        .sortBy(sort)
                        .scroll(position));
        final List<E> databaseDocuments = new ArrayList<>(window.getContent());
        ScrollPosition firstDatabasePosition = null;
        ScrollPosition lastDatabasePosition = null;
        if (!databaseDocuments.isEmpty()) {
            firstDatabasePosition = window.positionAt(0);
            lastDatabasePosition = window.positionAt(databaseDocuments.size() - 1);
        }
        final List<E> documents = new ArrayList<>(databaseDocuments);
        if (requestedDirection == ScrollPositionCodec.ScrollDirection.BACKWARD) {
            Collections.reverse(documents);
        }
        final List<D> responses = documents.stream()
                .<D>map(mapper)
                .toList();
        String nextScrollId = null;
        String previousScrollId = null;
        boolean hasNext = false;
        boolean hasPrevious = false;
        if (!documents.isEmpty()) {
            if (requestedDirection == ScrollPositionCodec.ScrollDirection.FORWARD) {
                if (window.hasNext()) {
                    nextScrollId = ScrollPositionCodec.encode(lastDatabasePosition, ScrollPositionCodec.ScrollDirection.FORWARD);
                    hasNext = true;
                }
                if (!initialRequest) {
                    previousScrollId = ScrollPositionCodec.encode(firstDatabasePosition, ScrollPositionCodec.ScrollDirection.BACKWARD);
                    hasPrevious = true;
                }
            } else {
                nextScrollId = ScrollPositionCodec.encode(firstDatabasePosition, ScrollPositionCodec.ScrollDirection.FORWARD);
                hasNext = true;
                if (window.hasNext()) {
                    previousScrollId = ScrollPositionCodec.encode(lastDatabasePosition, ScrollPositionCodec.ScrollDirection.BACKWARD);
                    hasPrevious = true;
                }
            }
        }
        return ScrollResponse.<D>builder()
                .contents(responses)
                .nextScrollId(nextScrollId)
                .prevScrollId(previousScrollId)
                .hasNext(hasNext)
                .hasPrevious(hasPrevious)
                .pageSize(pageSize)
                .build();
    }

    /**
     * Executes bidirectional keyset scrolling without specification and maps contents to DTOs.
     */
    public static <E, D> ScrollResponse<D> scroll(
            final JpaSpecificationExecutor<E> repository, final @Nullable String scrollId,
            final int pageSize, final String sortBy,
            final @Nullable String sortDirection, final @Nullable String scrollDirection,
            final @Nullable Collection<String> allowedSortProperties,
            final Function<? super E, ? extends D> mapper
    ) {
        return scroll(repository, Specification.unrestricted(), scrollId, pageSize, sortBy, sortDirection, scrollDirection, allowedSortProperties, mapper);
    }

    /**
     * Executes bidirectional keyset scrolling without specification and without mapping contents.
     */
    public static <E> ScrollResponse<E> scroll(
            final JpaSpecificationExecutor<E> repository, final @Nullable String scrollId,
            final int pageSize, final String sortBy,
            final @Nullable String sortDirection, final @Nullable String scrollDirection,
            final @Nullable Collection<String> allowedSortProperties
    ) {
        return scroll(repository, Specification.unrestricted(), scrollId, pageSize, sortBy, sortDirection, scrollDirection, allowedSortProperties, Function.identity());
    }

    /**
     * Executes bidirectional keyset scrolling using a {@link Sort} instance without content mapping.
     */
    public static <E> ScrollResponse<E> scroll(
            final JpaSpecificationExecutor<E> repository, final @Nullable Specification<E> specification,
            final @Nullable String scrollId, final int pageSize,
            final Sort sort, final ScrollPositionCodec.@Nullable ScrollDirection scrollDirection,
            final @Nullable Collection<String> allowedSortProperties
    ) {
        return scroll(repository, specification, scrollId, pageSize, sort, scrollDirection, allowedSortProperties, Function.identity());
    }

    /**
     * Executes bidirectional keyset scrolling using a {@link Sort} instance and {@link ScrollPositionCodec.ScrollDirection}.
     */
    public static <E, D> ScrollResponse<D> scroll(
            final JpaSpecificationExecutor<E> repository, final @Nullable Specification<E> specification,
            final @Nullable String scrollId, final int pageSize,
            final Sort sort, final ScrollPositionCodec.@Nullable ScrollDirection scrollDirection,
            final @Nullable Collection<String> allowedSortProperties,
            final Function<? super E, ? extends D> mapper
    ) {
        Assert.notNull(repository, "Repository must not be null");
        Assert.notNull(mapper, "Mapper must not be null");
        validatePageSize(pageSize);
        final Map<String, String> allowedProperties =
                normalizeAllowedSortProperties(allowedSortProperties);
        final Sort safeSort = validateSort(sort, allowedProperties);
        final ScrollPositionCodec.ScrollDirection requestedDirection =
                scrollDirection != null ? scrollDirection : ScrollPositionCodec.ScrollDirection.FORWARD;
        return executeScroll(repository, specification, scrollId, pageSize, safeSort, requestedDirection, mapper);
    }

    private static Sort validateSort(final @Nullable Sort sort, final Map<String, String> allowedSortProperties) {
        Assert.notNull(sort, "Sort must not be null");
        Assert.isTrue(sort.isSorted(), "Sorting is required for keyset scrolling");
        final List<Sort.Order> orders = sort.stream()
                .map(order -> {
                    final String canonicalProperty = validateSortBy(order.getProperty(), allowedSortProperties);
                    return order.withProperty(canonicalProperty);
                })
                .toList();
        return Sort.by(orders);
    }
}
