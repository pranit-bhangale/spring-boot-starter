package io.github.pranit.page.service;

import io.github.pranit.page.wrapper.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.util.Assert;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Provides pagination and content mapping utilities for Spring Data JPA.
 */
public final class Pagination {

    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MIN_PAGE_SIZE = 1;
    public static final int MAX_PAGE_SIZE = 100;

    private Pagination() {
        throw new UnsupportedOperationException("Pagination is a utility class and cannot be instantiated");
    }

    /**
     * Retrieves a page using a Spring Data Pageable without content mapping.
     */
    public static <E> PageResponse<E> paginate(
            final JpaRepository<E, ?> repository,
            final Pageable pageable,
            final Collection<String> allowedSortProperties
    ) {
        return paginate(repository, pageable, allowedSortProperties, Function.identity());
    }

    /**
     * Retrieves and maps a page using a Spring Data Pageable.
     */
    public static <E, D> PageResponse<D> paginate(
            final JpaRepository<E, ?> repository,
            final Pageable pageable,
            final Collection<String> allowedSortProperties,
            final Function<? super E, ? extends D> mapper
    ) {
        Assert.notNull(repository, "Repository must not be null");
        Assert.notNull(mapper, "Mapper must not be null");
        final Map<String, String> allowedProperties =
                normalizeAllowedSortProperties(allowedSortProperties);
        final Pageable safePageable =
                validatePageable(pageable, allowedProperties);
        final Page<E> result =
                repository.findAll(safePageable);
        return PageResponse.from(result, mapper);
    }

    private static Map<String, String> normalizeAllowedSortProperties(final Collection<String> allowedSortProperties) {
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

    private static Pageable validatePageable(final Pageable pageable, final Map<String, String> allowedSortProperties) {
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, DEFAULT_PAGE_SIZE);
        }
        validatePageNumber(pageable.getPageNumber());
        validatePageSize(pageable.getPageSize());
        final Sort safeSort = validateSort(pageable.getSort(), allowedSortProperties);
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), safeSort);
    }

    private static void validatePageNumber(final int page) {
        Assert.isTrue(page >= 0, "Page number must be greater than or equal to 0");
    }

    private static void validatePageSize(final int size) {
        Assert.isTrue(size >= MIN_PAGE_SIZE && size <= MAX_PAGE_SIZE,
                () -> "Page size must be between " + MIN_PAGE_SIZE + " and " + MAX_PAGE_SIZE);
    }

    private static Sort validateSort(final Sort sort, final Map<String, String> allowedSortProperties) {
        if (sort == null || sort.isUnsorted()) {
            return Sort.unsorted();
        }
        final List<Sort.Order> orders = sort.stream()
                .map(order -> {
                    final String canonicalProperty = validateSortBy(order.getProperty(), allowedSortProperties);
                    return order.withProperty(canonicalProperty);
                })
                .toList();
        return Sort.by(orders);
    }

    private static String validateSortBy(final String sortBy, final Map<String, String> allowedSortProperties) {
        Assert.hasText(sortBy, "Sort property must not be null or blank");
        final String trimmed = sortBy.trim();
        if (allowedSortProperties.isEmpty()) {
            throw new IllegalArgumentException("Sorting is not supported for this query");
        }
        final String canonical = allowedSortProperties.get(trimmed);
        Assert.isTrue(canonical != null, () -> "Unsupported sort property: " + trimmed);
        return canonical;
    }

    /**
     * Retrieves a page using a JPA specification and Spring Data Pageable without content mapping.
     * The specification may be null to indicate unconstrained filtering.
     */
    public static <E> PageResponse<E> paginate(
            final JpaSpecificationExecutor<E> repository,
            final Specification<E> specification,
            final Pageable pageable,
            final Collection<String> allowedSortProperties
    ) {
        return paginate(repository, specification, pageable, allowedSortProperties, Function.identity());
    }

    /**
     * Retrieves and maps a page using a JPA specification and Spring Data Pageable.
     * The specification may be null to indicate unconstrained filtering.
     */
    public static <E, D> PageResponse<D> paginate(
            final JpaSpecificationExecutor<E> repository,
            final Specification<E> specification,
            final Pageable pageable,
            final Collection<String> allowedSortProperties,
            final Function<? super E, ? extends D> mapper
    ) {
        Assert.notNull(repository, "Repository must not be null");
        Assert.notNull(mapper, "Mapper must not be null");
        final Map<String, String> allowedProperties = normalizeAllowedSortProperties(allowedSortProperties);
        final Pageable safePageable = validatePageable(pageable, allowedProperties);
        final Page<E> result = repository.findAll(specification, safePageable);
        return PageResponse.from(result, mapper);
    }

    /**
     * Retrieves a page using a JPA specification and explicit pagination parameters without mapping.
     */
    public static <E> PageResponse<E> paginate(
            final JpaSpecificationExecutor<E> repository,
            final Specification<E> specification,
            final int page,
            final int size,
            final String sortBy,
            final String sortDirection,
            final Collection<String> allowedSortProperties
    ) {
        return paginate(repository, specification, page, size, sortBy, sortDirection, allowedSortProperties, Function.identity());
    }

    /**
     * Retrieves and maps a page using a JPA specification and explicit
     * pagination and sorting parameters.
     */
    public static <E, D> PageResponse<D> paginate(
            final JpaSpecificationExecutor<E> repository,
            final Specification<E> specification,
            final int page,
            final int size,
            final String sortBy,
            final String sortDirection,
            final Collection<String> allowedSortProperties,
            final Function<? super E, ? extends D> mapper
    ) {
        Assert.notNull(repository, "Repository must not be null");
        Assert.notNull(mapper, "Mapper must not be null");
        validatePageNumber(page);
        validatePageSize(size);

        final Map<String, String> allowedProperties = normalizeAllowedSortProperties(allowedSortProperties);
        final Sort safeSort;
        if (sortBy != null && !sortBy.isBlank()) {
            final String canonicalProperty = validateSortBy(sortBy, allowedProperties);
            final Sort.Direction direction = parseSortDirection(sortDirection);
            safeSort = Sort.by(direction, canonicalProperty);
        } else {
            safeSort = Sort.unsorted();
        }

        final Pageable pageable = PageRequest.of(page, size, safeSort);
        final Page<E> result = repository.findAll(specification, pageable);
        return PageResponse.from(result, mapper);
    }

    private static Sort.Direction parseSortDirection(final String sortDirection) {
        if (sortDirection == null || sortDirection.isBlank()) {
            return Sort.Direction.ASC;
        }
        return switch (sortDirection.trim().toLowerCase(Locale.ROOT)) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw new IllegalArgumentException("Sort direction must be 'asc' or 'desc'");
        };
    }
}