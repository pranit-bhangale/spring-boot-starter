package io.github.pranit.page;

import io.github.pranit.page.service.Page;
import io.github.pranit.page.wrapper.PageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PageMockTest {

    @Mock
    private JpaRepository<TestEntity, Long> repository;
    @Mock
    private JpaSpecificationExecutor<TestEntity> specificationExecutor;

    record TestEntity(Long id, String name, String createdAt) {
    }

    record TestDto(Long id, String name) {
    }

    @Nested
    @DisplayName("Utility Class Invariants")
    class UtilityClassTests {

        @Test
        @DisplayName("Private constructor should throw UnsupportedOperationException via reflection")
        void privateConstructorShouldThrowException() throws Exception {
            Constructor<Page> constructor = Page.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            assertThatThrownBy(constructor::newInstance)
                    .isInstanceOf(InvocationTargetException.class)
                    .hasCauseInstanceOf(UnsupportedOperationException.class)
                    .hasRootCauseMessage("Pagination is a utility class and cannot be instantiated");
        }

        @Test
        @DisplayName("Pagination constants should have expected values")
        void constantsShouldHaveExpectedValues() {
            assertThat(Page.DEFAULT_PAGE_SIZE).isEqualTo(10);
            assertThat(Page.MIN_PAGE_SIZE).isEqualTo(1);
            assertThat(Page.MAX_PAGE_SIZE).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("Repository-based Pagination")
    class RepositoryPageTests {

        @Test
        @DisplayName("Should paginate and map content when pageable and sort are valid")
        void shouldPaginateAndMapContent() {
            List<TestEntity> entities = List.of(
                    new TestEntity(1L, "Alpha", "2026-01-01"),
                    new TestEntity(2L, "Beta", "2026-01-02")
            );
            org.springframework.data.domain.Page<TestEntity> entityPage = new PageImpl<>(entities, PageRequest.of(0, 10), 2);
            when(repository.findAll(any(Pageable.class))).thenReturn(entityPage);
            Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
            PageResponse<TestDto> response = Page.paginate(
                    repository, pageable, Set.of("id", "name", "createdAt"),
                    entity -> new TestDto(entity.id(), entity.name()));
            assertThat(response).isNotNull();
            assertThat(response.contents()).hasSize(2);
            assertThat(response.contents().getFirst()).isEqualTo(new TestDto(1L, "Alpha"));
            assertThat(response.currentPage()).isZero();
            assertThat(response.pageSize()).isEqualTo(10);
            assertThat(response.totalElements()).isEqualTo(2);
            assertThat(response.totalPages()).isEqualTo(1);
            assertThat(response.isFirstPage()).isTrue();
            assertThat(response.isLastPage()).isTrue();
            assertThat(response.hasNext()).isFalse();
            assertThat(response.hasPrevious()).isFalse();
        }

        @Test
        @DisplayName("Should support case-insensitive sort property matching and resolve to canonical property")
        void shouldSupportCaseInsensitiveSortMatching() {
            org.springframework.data.domain.Page<TestEntity> emptyPage = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 10), 0);
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            when(repository.findAll(captor.capture())).thenReturn(emptyPage);
            Pageable pageable = PageRequest.of(0, 10, Sort.by("createdat"));
            Page.paginate(repository, pageable, List.of("createdAt"), TestEntity::id);
            Pageable captured = captor.getValue();
            Sort.Order order = captured.getSort().getOrderFor("createdAt");
            assertThat(order).isNotNull();
            assertThat(order.getProperty()).isEqualTo("createdAt");
        }

        @Test
        @DisplayName("Should support multi-property sorting")
        void shouldSupportMultiPropertySorting() {
            org.springframework.data.domain.Page<TestEntity> emptyPage = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 10), 0);
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            when(repository.findAll(captor.capture())).thenReturn(emptyPage);
            Sort multiSort = Sort.by(Sort.Direction.ASC, "name")
                    .and(Sort.by(Sort.Direction.DESC, "createdat"));
            Pageable pageable = PageRequest.of(0, 10, multiSort);
            Page.paginate(repository, pageable, List.of("name", "createdAt"), TestEntity::id);
            Pageable captured = captor.getValue();
            assertThat(captured.getSort().getOrderFor("name")).isNotNull();
            assertThat(Objects.requireNonNull(captured.getSort().getOrderFor("name"))
                    .getDirection()).isEqualTo(Sort.Direction.ASC);
            assertThat(captured.getSort().getOrderFor("createdAt")).isNotNull();
            assertThat(Objects.requireNonNull(captured.getSort().getOrderFor("createdAt"))
                    .getDirection()).isEqualTo(Sort.Direction.DESC);
        }

        @Test
        @DisplayName("Should paginate without mapper overload")
        void shouldPaginateWithoutMapper() {
            List<TestEntity> entities = List.of(new TestEntity(1L, "Alpha", "2026-01-01"));
            org.springframework.data.domain.Page<TestEntity> entityPage = new PageImpl<>(entities, PageRequest.of(0, 10), 1);
            when(repository.findAll(any(Pageable.class))).thenReturn(entityPage);
            PageResponse<TestEntity> response = Page.paginate(
                    repository, PageRequest.of(0, 10), List.of("id"));
            assertThat(response.contents()).containsExactlyElementsOf(entities);
        }

        @Test
        @DisplayName("Should fallback to default page and size when pageable is null")
        void shouldFallbackWhenPageableIsNull() {
            org.springframework.data.domain.Page<TestEntity> emptyPage = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 10), 0);
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            when(repository.findAll(captor.capture())).thenReturn(emptyPage);
            Page.paginate(repository, null, List.of("id"), TestEntity::id);
            Pageable captured = captor.getValue();
            assertThat(captured.getPageNumber()).isZero();
            assertThat(captured.getPageSize()).isEqualTo(Page.DEFAULT_PAGE_SIZE);
            assertThat(captured.getSort().isUnsorted()).isTrue();
        }

        @Test
        @DisplayName("Should fallback to default page and size when pageable is unpaged")
        void shouldFallbackWhenPageableIsUnpaged() {
            org.springframework.data.domain.Page<TestEntity> emptyPage = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 10), 0);
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            when(repository.findAll(captor.capture())).thenReturn(emptyPage);
            Page.paginate(repository, Pageable.unpaged(), List.of("id"), TestEntity::id);
            Pageable captured = captor.getValue();
            assertThat(captured.getPageNumber()).isZero();
            assertThat(captured.getPageSize()).isEqualTo(Page.DEFAULT_PAGE_SIZE);
            assertThat(captured.getSort().isUnsorted()).isTrue();
        }

        @Test
        @DisplayName("Should allow unsorted queries even if allowed sort properties are empty")
        void shouldAllowUnsortedQueriesWhenAllowedSortEmpty() {
            org.springframework.data.domain.Page<TestEntity> emptyPage = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 10), 0);
            when(repository.findAll(any(Pageable.class))).thenReturn(emptyPage);
            PageResponse<Long> response = Page.paginate(
                    repository, PageRequest.of(0, 10, Sort.unsorted()),
                    Collections.emptyList(), TestEntity::id);
            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("Should allow unsorted queries even if allowed sort properties are null")
        void shouldAllowUnsortedQueriesWhenAllowedSortNull() {
            org.springframework.data.domain.Page<TestEntity> emptyPage = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 10), 0);
            when(repository.findAll(any(Pageable.class))).thenReturn(emptyPage);
            PageResponse<Long> response = Page.paginate(
                    repository, PageRequest.of(0, 10, Sort.unsorted()),
                    null, TestEntity::id);
            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("Should throw exception when sorting is requested but allowed sort properties are empty")
        void shouldThrowWhenSortRequestedWithEmptyAllowed() {
            Pageable pageable = PageRequest.of(0, 10, Sort.by("id"));
            assertThatThrownBy(() -> Page.paginate(repository, pageable, Collections.emptyList(), TestEntity::id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Sorting is not supported for this query");
        }

        @Test
        @DisplayName("Should throw exception when sorting is requested but allowed sort properties are null")
        void shouldThrowWhenSortRequestedWithNullAllowed() {
            Pageable pageable = PageRequest.of(0, 10, Sort.by("id"));
            assertThatThrownBy(() -> Page.paginate(repository, pageable, null, TestEntity::id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Sorting is not supported for this query");
        }

        @Test
        @DisplayName("Should reject unauthorized sort property")
        void shouldRejectUnauthorizedSortProperty() {
            Pageable pageable = PageRequest.of(0, 10, Sort.by("secretField"));
            assertThatThrownBy(() -> Page.paginate(repository, pageable, Set.of("id", "name"), TestEntity::id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Unsupported sort property: secretField");
        }

        @Test
        @DisplayName("Should reject blank or null sort property names in allowedSortProperties")
        void shouldRejectBlankAllowedSortProperty() {
            Pageable pageable = PageRequest.of(0, 10);
            assertThatThrownBy(() -> Page.paginate(repository, pageable, List.of("id", "  "), TestEntity::id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Allowed sort properties must not be null or blank");
        }

        @Test
        @DisplayName("Should reject page size exceeding maximum limit")
        void shouldRejectExcessivePageSize() {
            Pageable pageable = PageRequest.of(0, 101);
            assertThatThrownBy(() -> Page.paginate(repository, pageable, List.of("id"), TestEntity::id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Page size must be between 1 and 100");
        }

        @Test
        @DisplayName("Should reject page size below minimum limit")
        void shouldRejectUnderMinimumPageSize() {
            Pageable mockPageable = mock(Pageable.class);
            when(mockPageable.isUnpaged()).thenReturn(false);
            when(mockPageable.getPageNumber()).thenReturn(0);
            when(mockPageable.getPageSize()).thenReturn(0);
            assertThatThrownBy(() -> Page.paginate(repository, mockPageable, List.of("id"), TestEntity::id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Page size must be between 1 and 100");
        }

        @Test
        @DisplayName("Should reject negative page number")
        void shouldRejectNegativePageNumber() {
            Pageable mockPageable = mock(Pageable.class);
            when(mockPageable.isUnpaged()).thenReturn(false);
            when(mockPageable.getPageNumber()).thenReturn(-1);
            assertThatThrownBy(() -> Page.paginate(repository, mockPageable, List.of("id"), TestEntity::id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Page number must be greater than or equal to 0");
        }

        @Test
        @DisplayName("Should reject null repository")
        void shouldRejectNullRepository() {
            Pageable pageable = PageRequest.of(0, 10);
            assertThatThrownBy(() -> Page.paginate(null, pageable, List.of("id"), TestEntity::id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Repository must not be null");
        }

        @Test
        @DisplayName("Should reject null mapper")
        void shouldRejectNullMapper() {
            Pageable pageable = PageRequest.of(0, 10);
            assertThatThrownBy(() -> Page.paginate(repository, pageable, List.of("id"), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Mapper must not be null");
        }

        @Test
        @DisplayName("Should reject null element in allowedSortProperties")
        void shouldRejectNullElementInAllowedSortProperties() {
            Pageable pageable = PageRequest.of(0, 10);
            List<String> allowedWithNull = Collections.singletonList(null);
            assertThatThrownBy(() -> Page.paginate(repository, pageable, allowedWithNull, TestEntity::id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Allowed sort properties must not be null or blank");
        }

        @Test
        @DisplayName("Should handle null sort from Pageable gracefully")
        void shouldHandleNullSortGracefully() {
            org.springframework.data.domain.Page<TestEntity> emptyPage = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 10), 0);
            when(repository.findAll(any(Pageable.class))).thenReturn(emptyPage);

            Pageable mockPageable = mock(Pageable.class);
            when(mockPageable.isUnpaged()).thenReturn(false);
            when(mockPageable.getPageNumber()).thenReturn(0);
            when(mockPageable.getPageSize()).thenReturn(10);
            when(mockPageable.getSort()).thenReturn(null);

            PageResponse<Long> response = Page.paginate(repository, mockPageable, List.of("id"), TestEntity::id);
            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("Should trim sort property name and resolve correctly")
        void shouldTrimSortPropertyName() {
            org.springframework.data.domain.Page<TestEntity> emptyPage = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 10), 0);
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            when(repository.findAll(captor.capture())).thenReturn(emptyPage);

            Pageable pageable = PageRequest.of(0, 10, Sort.by("  name  "));
            Page.paginate(repository, pageable, List.of("name"), TestEntity::id);

            Pageable captured = captor.getValue();
            assertThat(captured.getSort().getOrderFor("name")).isNotNull();
            assertThat(Objects.requireNonNull(captured.getSort().getOrderFor("name")).getProperty()).isEqualTo("name");
        }

        @Test
        @DisplayName("Should reject blank sort property in Sort order")
        void shouldRejectBlankSortPropertyInSort() {
            Sort.Order mockOrder = mock(Sort.Order.class);
            when(mockOrder.getProperty()).thenReturn("   ");
            Pageable pageable = PageRequest.of(0, 10, Sort.by(List.of(mockOrder)));
            assertThatThrownBy(() -> Page.paginate(repository, pageable, List.of("name"), TestEntity::id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Sort property must not be null or blank");
        }

        @Test
        @DisplayName("Should accept valid boundary page sizes MIN_PAGE_SIZE and MAX_PAGE_SIZE")
        @SuppressWarnings("unchecked")
        void shouldAcceptBoundaryPageSizes() {
            org.springframework.data.domain.Page<TestEntity> emptyPageMin = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, Page.MIN_PAGE_SIZE), 0);
            org.springframework.data.domain.Page<TestEntity> emptyPageMax = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, Page.MAX_PAGE_SIZE), 0);
            when(repository.findAll(any(Pageable.class))).thenReturn(emptyPageMin, emptyPageMax);

            PageResponse<Long> minResponse = Page.paginate(repository, PageRequest.of(0, Page.MIN_PAGE_SIZE), List.of("id"), TestEntity::id);
            assertThat(minResponse.pageSize()).isEqualTo(Page.MIN_PAGE_SIZE);

            PageResponse<Long> maxResponse = Page.paginate(repository, PageRequest.of(0, Page.MAX_PAGE_SIZE), List.of("id"), TestEntity::id);
            assertThat(maxResponse.pageSize()).isEqualTo(Page.MAX_PAGE_SIZE);
        }
    }

    @Nested
    @DisplayName("Specification-based Pagination")
    class SpecificationPageTests {

        @Test
        @DisplayName("Should support Specification with Pageable and non-null specification")
        @SuppressWarnings("unchecked")
        void shouldSupportSpecWithPageableAndNonNullSpec() {
            org.springframework.data.domain.Page<TestEntity> emptyPage = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 10), 0);
            Specification<TestEntity> spec = mock(Specification.class);
            when(specificationExecutor.findAll(eq(spec), any(Pageable.class))).thenReturn(emptyPage);
            PageResponse<TestDto> response = Page.paginate(
                    specificationExecutor, spec,
                    PageRequest.of(0, 10), List.of("id"),
                    entity -> new TestDto(entity.id(), entity.name()));
            assertThat(response).isNotNull();
            verify(specificationExecutor).findAll(eq(spec), any(Pageable.class));
        }

        @Test
        @DisplayName("Should support Specification with Pageable and null specification")
        void shouldSupportSpecWithPageableAndNullSpec() {
            org.springframework.data.domain.Page<TestEntity> emptyPage = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 20), 0);
            when(specificationExecutor.findAll(any(), any(Pageable.class))).thenReturn(emptyPage);
            PageResponse<TestEntity> response = Page.paginate(
                    specificationExecutor, null, PageRequest.of(0, 20),
                    List.of("id"));
            assertThat(response).isNotNull();
            verify(specificationExecutor).findAll(any(), any(Pageable.class));
        }

        @Test
        @DisplayName("Should reject null repository in specification with Pageable overload")
        void shouldRejectNullRepoInSpecWithPageable() {
            Pageable pageable = PageRequest.of(0, 10);
            assertThatThrownBy(() -> Page.paginate(null, null, pageable, List.of("id"), TestEntity::id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Repository must not be null");
        }

        @Test
        @DisplayName("Should reject null mapper in specification with Pageable overload")
        void shouldRejectNullMapperInSpecWithPageable() {
            Pageable pageable = PageRequest.of(0, 10);
            assertThatThrownBy(() -> Page.paginate(specificationExecutor, null, pageable, List.of("id"), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Mapper must not be null");
        }

        @Test
        @DisplayName("Should support primitive parameter specification overload with optional sort")
        void shouldSupportPrimitiveSpecificationParams() {
            org.springframework.data.domain.Page<TestEntity> emptyPage = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 15), 0);
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            when(specificationExecutor.findAll(any(), captor.capture())).thenReturn(emptyPage);
            Page.paginate(specificationExecutor, null, 0, 15,
                    null, null, List.of("id", "name"));
            Pageable captured = captor.getValue();
            assertThat(captured.getPageNumber()).isZero();
            assertThat(captured.getPageSize()).isEqualTo(15);
            assertThat(captured.getSort().isUnsorted()).isTrue();
        }

        @Test
        @DisplayName("Should support primitive parameter specification overload without mapper")
        void shouldSupportPrimitiveSpecificationParamsWithoutMapper() {
            List<TestEntity> entities = List.of(new TestEntity(1L, "Beta", "2026-01-02"));
            org.springframework.data.domain.Page<TestEntity> entityPage = new PageImpl<>(entities, PageRequest.of(0, 10), 1);
            when(specificationExecutor.findAll(any(), any(Pageable.class))).thenReturn(entityPage);
            PageResponse<TestEntity> response = Page.paginate(
                    specificationExecutor, null, 0, 10,
                    "id", "desc", List.of("id"));
            assertThat(response.contents()).containsExactlyElementsOf(entities);
        }

        @Test
        @DisplayName("Should default to ASC when sortBy is provided but sortDirection is omitted or blank")
        void shouldDefaultToAscWhenDirectionOmitted() {
            org.springframework.data.domain.Page<TestEntity> emptyPage = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 15), 0);
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            when(specificationExecutor.findAll(any(), captor.capture())).thenReturn(emptyPage);
            Page.paginate(
                    specificationExecutor, null, 0, 15,
                    "name", "   ", List.of("id", "name"), TestEntity::name);
            Pageable captured = captor.getValue();
            Sort.Order order = captured.getSort().getOrderFor("name");
            assertThat(order).isNotNull();
            assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
        }

        @Test
        @DisplayName("Should parse DESC direction case-insensitively")
        void shouldParseDescDirectionCaseInsensitively() {
            org.springframework.data.domain.Page<TestEntity> emptyPage = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 15), 0);
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            when(specificationExecutor.findAll(any(), captor.capture())).thenReturn(emptyPage);
            Page.paginate(specificationExecutor, null,
                    0, 15, "name", "DeSc",
                    List.of("id", "name"), TestEntity::name);
            Pageable captured = captor.getValue();
            Sort.Order order = captured.getSort().getOrderFor("name");
            assertThat(order).isNotNull();
            assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
        }

        @Test
        @DisplayName("Should reject invalid sort direction")
        void shouldRejectInvalidSortDirection() {
            assertThatThrownBy(() -> Page.paginate(specificationExecutor, null,
                    0, 15, "name", "sideways",
                    List.of("id", "name"), TestEntity::name))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Sort direction must be 'asc' or 'desc'");
        }

        @Test
        @DisplayName("Should reject null repository in primitive specification overload")
        void shouldRejectNullRepoInPrimitiveSpec() {
            assertThatThrownBy(() -> Page.paginate(null, null,
                    0, 10, "id", "asc",
                    List.of("id"), TestEntity::id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Repository must not be null");
        }

        @Test
        @DisplayName("Should reject null mapper in primitive specification overload")
        void shouldRejectNullMapperInPrimitiveSpec() {
            assertThatThrownBy(() -> Page.paginate(specificationExecutor, null,
                    0, 10, "id", "asc",
                    List.of("id"), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Mapper must not be null");
        }

        @Test
        @DisplayName("Should reject negative page number in primitive specification overload")
        void shouldRejectNegativePageNumberInPrimitiveSpec() {
            assertThatThrownBy(() -> Page.paginate(specificationExecutor, null, -1, 10, "id", "asc", List.of("id"), TestEntity::id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Page number must be greater than or equal to 0");
        }

        @Test
        @DisplayName("Should reject under-minimum page size in primitive specification overload")
        void shouldRejectUnderMinimumPageSizeInPrimitiveSpec() {
            assertThatThrownBy(() -> Page.paginate(specificationExecutor, null, 0, 0, "id", "asc", List.of("id"), TestEntity::id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Page size must be between 1 and 100");
        }

        @Test
        @DisplayName("Should reject excessive page size in primitive specification overload")
        void shouldRejectExcessivePageSizeInPrimitiveSpec() {
            assertThatThrownBy(() -> Page.paginate(specificationExecutor, null, 0, 101, "id", "asc", List.of("id"), TestEntity::id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Page size must be between 1 and 100");
        }

        @Test
        @DisplayName("Should treat blank sortBy as unsorted in primitive specification overload")
        void shouldTreatBlankSortByAsUnsortedInPrimitiveSpec() {
            org.springframework.data.domain.Page<TestEntity> emptyPage = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 10), 0);
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            when(specificationExecutor.findAll(any(Specification.class), captor.capture())).thenReturn(emptyPage);

            Page.paginate(specificationExecutor, null, 0, 10, "   ", "asc", List.of("id"), TestEntity::id);
            assertThat(captor.getValue().getSort().isUnsorted()).isTrue();
        }

        @Test
        @DisplayName("Should parse ASC direction correctly and case-insensitively in primitive specification overload")
        void shouldParseAscDirectionCorrectly() {
            org.springframework.data.domain.Page<TestEntity> emptyPage = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 10), 0);
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            when(specificationExecutor.findAll(any(Specification.class), captor.capture())).thenReturn(emptyPage);

            Page.paginate(specificationExecutor, null, 0, 10, "id", "AsC", List.of("id"), TestEntity::id);
            Sort.Order order = captor.getValue().getSort().getOrderFor("id");
            assertThat(order).isNotNull();
            assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
        }

        @Test
        @DisplayName("Should default to ASC when sortDirection is empty in primitive specification overload")
        void shouldDefaultToAscWhenDirectionEmpty() {
            org.springframework.data.domain.Page<TestEntity> emptyPage = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 10), 0);
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            when(specificationExecutor.findAll(any(Specification.class), captor.capture())).thenReturn(emptyPage);

            Page.paginate(specificationExecutor, null, 0, 10, "id", "", List.of("id"), TestEntity::id);
            Sort.Order order = captor.getValue().getSort().getOrderFor("id");
            assertThat(order).isNotNull();
            assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
        }
    }

    @Nested
    @DisplayName("PageResponse Functionality")
    class PageResponseTests {

        @Test
        @DisplayName("Should correctly compute pagination helper methods and map content")
        void shouldComputeHelperMethods() {
            List<String> items = List.of("One", "Two", "Three");
            org.springframework.data.domain.Page<String> page = new PageImpl<>(items, PageRequest.of(1, 3), 10);
            PageResponse<String> response = PageResponse.from(page, String::toUpperCase);
            assertThat(response.contents()).containsExactly("ONE", "TWO", "THREE");
            assertThat(response.currentPage()).isEqualTo(1);
            assertThat(response.pageSize()).isEqualTo(3);
            assertThat(response.totalElements()).isEqualTo(10);
            assertThat(response.totalPages()).isEqualTo(4);
            assertThat(response.isFirstPage()).isFalse();
            assertThat(response.isLastPage()).isFalse();
            assertThat(response.hasNext()).isTrue();
            assertThat(response.hasPrevious()).isTrue();
            assertThat(response.numberOfElements()).isEqualTo(3);
            assertThat(response.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("Should support PageResponse.from without mapper")
        void shouldSupportFromWithoutMapper() {
            List<String> items = List.of("Alpha", "Beta");
            org.springframework.data.domain.Page<String> page = new PageImpl<>(items, PageRequest.of(0, 2), 2);
            PageResponse<String> response = PageResponse.from(page);
            assertThat(response.contents()).containsExactly("Alpha", "Beta");
            assertThat(response.hasNext()).isFalse();
            assertThat(response.hasPrevious()).isFalse();
        }

        @Test
        @DisplayName("Should handle empty page correctly")
        void shouldHandleEmptyPage() {
            PageResponse<String> response = PageResponse.from(org.springframework.data.domain.Page.empty());
            assertThat(response.contents()).isEmpty();
            assertThat(response.currentPage()).isZero();
            assertThat(response.totalElements()).isZero();
            assertThat(response.totalPages()).isEqualTo(org.springframework.data.domain.Page.empty().getTotalPages());
            assertThat(response.isFirstPage()).isTrue();
            assertThat(response.isLastPage()).isTrue();
            assertThat(response.hasNext()).isFalse();
            assertThat(response.hasPrevious()).isFalse();
            assertThat(response.isEmpty()).isTrue();
            assertThat(response.numberOfElements()).isZero();
        }

        @Test
        @DisplayName("Should throw when page or mapper is null in PageResponse.from")
        void shouldThrowWhenPageOrMapperNull() {
            assertThatThrownBy(() -> PageResponse.from(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Page must not be null");
            org.springframework.data.domain.Page<String> page = new PageImpl<>(List.of("A"));
            assertThatThrownBy(() -> PageResponse.from(page, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Mapper must not be null");
        }

        @Test
        @DisplayName("Should guarantee contents immutability and non-null safety")
        void shouldGuaranteeContentsImmutability() {
            PageResponse<String> response = new PageResponse<>(null, 0, 10, 0, 0, true, true);
            assertThat(response.contents()).isNotNull();
            assertThat(response.contents()).isEmpty();
            assertThat(response.isEmpty()).isTrue();
            assertThatThrownBy(() -> response.contents().add("disallowed"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Should validate numeric boundaries in PageResponse constructor")
        void shouldValidateNumericBoundaries() {
            assertThatThrownBy(() -> new PageResponse<>(List.of(), -1, 10, 0, 0, true, true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Current page must be >= 0");
            assertThatThrownBy(() -> new PageResponse<>(List.of(), 0, -1, 0, 0, true, true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Page size must be >= 0");
            assertThatThrownBy(() -> new PageResponse<>(List.of(), 0, 10, -1, 0, true, true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Total elements must be >= 0");
            assertThatThrownBy(() -> new PageResponse<>(List.of(), 0, 10, 0, -1, true, true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Total pages must be >= 0");
        }

        @Test
        @DisplayName("Should build valid PageResponse using Builder")
        void shouldBuildUsingBuilder() {
            PageResponse<String> response = PageResponse.<String>builder()
                    .contents(List.of("Item"))
                    .currentPage(2)
                    .pageSize(5)
                    .totalElements(15)
                    .totalPages(3)
                    .isFirstPage(false)
                    .isLastPage(true)
                    .build();
            assertThat(response.contents()).containsExactly("Item");
            assertThat(response.currentPage()).isEqualTo(2);
            assertThat(response.pageSize()).isEqualTo(5);
            assertThat(response.totalElements()).isEqualTo(15);
            assertThat(response.totalPages()).isEqualTo(3);
            assertThat(response.isFirstPage()).isFalse();
            assertThat(response.isLastPage()).isTrue();
            assertThat(response.hasNext()).isFalse();
            assertThat(response.hasPrevious()).isTrue();
        }

        @Test
        @DisplayName("Should verify record equals, hashCode, and toString")
        void shouldVerifyRecordEqualsHashCodeAndToString() {
            PageResponse<String> response1 = new PageResponse<>(List.of("A"), 0, 10, 1, 1, true, true);
            PageResponse<String> response2 = new PageResponse<>(List.of("A"), 0, 10, 1, 1, true, true);
            PageResponse<String> response3 = new PageResponse<>(List.of("B"), 0, 10, 1, 1, true, true);

            assertThat(response1).isEqualTo(response2);
            assertThat(response1.hashCode()).isEqualTo(response2.hashCode());
            assertThat(response1).isNotEqualTo(response3);
            assertThat(response1.toString()).contains("PageResponse", "A", "totalElements=1");
        }
    }
}
