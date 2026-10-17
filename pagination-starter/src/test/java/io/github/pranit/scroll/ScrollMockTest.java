package io.github.pranit.scroll;

import io.github.pranit.scroll.exception.InvalidScrollingException;
import io.github.pranit.scroll.service.Scroll;
import io.github.pranit.scroll.service.ScrollPositionCodec;
import io.github.pranit.scroll.service.ScrollPositionCodec.ScrollDirection;
import io.github.pranit.scroll.wrapper.ScrollResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScrollMockTest {

    @Mock
    private JpaSpecificationExecutor<DocumentEntity> documentRepository;

    record DocumentEntity(Long id, String title, Instant createdAt) {
    }

    record DocumentDto(Long id, String title) {
    }

    @Nested
    @DisplayName("Scroll Utility Class Invariants")
    class ScrollUtilityInvariantsTests {

        @Test
        @DisplayName("Private constructor should throw UnsupportedOperationException via reflection")
        void privateConstructorShouldThrowException() throws Exception {
            Constructor<Scroll> constructor = Scroll.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            assertThatThrownBy(constructor::newInstance)
                    .isInstanceOf(InvocationTargetException.class)
                    .hasCauseInstanceOf(UnsupportedOperationException.class)
                    .hasRootCauseMessage("Scroll is a utility class and cannot be instantiated");
        }

        @Test
        @DisplayName("Constants should have expected default values")
        void constantsShouldHaveExpectedValues() {
            assertThat(Scroll.DEFAULT_PAGE_SIZE).isEqualTo(10);
            assertThat(Scroll.MIN_PAGE_SIZE).isEqualTo(1);
            assertThat(Scroll.MAX_PAGE_SIZE).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("Bidirectional Keyset Scrolling Execution")
    class ScrollingExecutionTests {

        @Test
        @DisplayName("Initial forward scroll should return nextScrollId and hasNext when more items exist")
        @SuppressWarnings("unchecked")
        void shouldExecuteInitialForwardScrollWithNextPage() {
            List<DocumentEntity> entities = List.of(
                    new DocumentEntity(1L, "Doc A", Instant.parse("2026-01-01T00:00:00Z")),
                    new DocumentEntity(2L, "Doc B", Instant.parse("2026-01-02T00:00:00Z"))
            );
            ScrollPosition pos0 = ScrollPosition.forward(Map.of("id", 1L));
            ScrollPosition pos1 = ScrollPosition.forward(Map.of("id", 2L));

            Window<DocumentEntity> window = mock(Window.class);
            when(window.getContent()).thenReturn(entities);
            when(window.positionAt(0)).thenReturn(pos0);
            when(window.positionAt(1)).thenReturn(pos1);
            when(window.hasNext()).thenReturn(true);

            when(documentRepository.findBy(any(Specification.class), any(Function.class)))
                    .thenAnswer(invocation -> {
                        Function<JpaSpecificationExecutor.SpecificationFluentQuery<DocumentEntity>, Window<DocumentEntity>> queryFn = invocation.getArgument(1);
                        JpaSpecificationExecutor.SpecificationFluentQuery<DocumentEntity> query = mock(JpaSpecificationExecutor.SpecificationFluentQuery.class);
                        when(query.limit(2)).thenReturn(query);
                        when(query.sortBy(any(Sort.class))).thenReturn(query);
                        when(query.scroll(any(ScrollPosition.class))).thenReturn(window);
                        return queryFn.apply(query);
                    });

            ScrollResponse<DocumentDto> response = Scroll.scroll(
                    documentRepository,
                    null,
                    null,
                    2,
                    "id",
                    "asc",
                    "forward",
                    Set.of("id", "title"),
                    entity -> new DocumentDto(entity.id(), entity.title())
            );

            assertThat(response).isNotNull();
            assertThat(response.contents()).containsExactly(
                    new DocumentDto(1L, "Doc A"),
                    new DocumentDto(2L, "Doc B")
            );
            assertThat(response.hasNext()).isTrue();
            assertThat(response.nextScrollId()).isNotNull();
            assertThat(response.hasPrevious()).isFalse();
            assertThat(response.prevScrollId()).isNull();
            assertThat(response.pageSize()).isEqualTo(2);

            ScrollPositionCodec.DecodedScrollPosition nextDecoded = ScrollPositionCodec.decode(response.nextScrollId());
            assertThat(nextDecoded.direction()).isEqualTo(ScrollDirection.FORWARD);
            assertThat(((KeysetScrollPosition) nextDecoded.position()).getKeys()).containsEntry("id", 2L);
        }

        @Test
        @DisplayName("Subsequent forward scroll should provide both nextScrollId and prevScrollId")
        @SuppressWarnings("unchecked")
        void shouldExecuteSubsequentForwardScroll() {
            List<DocumentEntity> entities = List.of(
                    new DocumentEntity(3L, "Doc C", Instant.parse("2026-01-03T00:00:00Z")),
                    new DocumentEntity(4L, "Doc D", Instant.parse("2026-01-04T00:00:00Z"))
            );
            ScrollPosition pos0 = ScrollPosition.forward(Map.of("id", 3L));
            ScrollPosition pos1 = ScrollPosition.forward(Map.of("id", 4L));

            Window<DocumentEntity> window = mock(Window.class);
            when(window.getContent()).thenReturn(entities);
            when(window.positionAt(0)).thenReturn(pos0);
            when(window.positionAt(1)).thenReturn(pos1);
            when(window.hasNext()).thenReturn(true);

            when(documentRepository.findBy(any(Specification.class), any(Function.class)))
                    .thenAnswer(invocation -> {
                        Function<JpaSpecificationExecutor.SpecificationFluentQuery<DocumentEntity>, Window<DocumentEntity>> queryFn = invocation.getArgument(1);
                        JpaSpecificationExecutor.SpecificationFluentQuery<DocumentEntity> query = mock(JpaSpecificationExecutor.SpecificationFluentQuery.class);
                        when(query.limit(2)).thenReturn(query);
                        when(query.sortBy(any(Sort.class))).thenReturn(query);
                        when(query.scroll(any(ScrollPosition.class))).thenReturn(window);
                        return queryFn.apply(query);
                    });

            String scrollId = ScrollPositionCodec.encode(pos0, ScrollDirection.FORWARD);

            ScrollResponse<DocumentDto> response = Scroll.scroll(
                    documentRepository,
                    null,
                    scrollId,
                    2,
                    "id",
                    "asc",
                    "forward",
                    Set.of("id", "title"),
                    entity -> new DocumentDto(entity.id(), entity.title())
            );

            assertThat(response.hasNext()).isTrue();
            assertThat(response.nextScrollId()).isNotNull();
            assertThat(response.hasPrevious()).isTrue();
            assertThat(response.prevScrollId()).isNotNull();

            ScrollPositionCodec.DecodedScrollPosition prevDecoded = ScrollPositionCodec.decode(response.prevScrollId());
            assertThat(prevDecoded.direction()).isEqualTo(ScrollDirection.BACKWARD);
            assertThat(((KeysetScrollPosition) prevDecoded.position()).getKeys()).containsEntry("id", 3L);
        }

        @Test
        @DisplayName("Backward scroll should reverse database records and set nextScrollId towards forward view")
        @SuppressWarnings("unchecked")
        void shouldExecuteBackwardScrollAndReverseContent() {
            // DB returns descending items when scrolling backward: Doc B (2), Doc A (1)
            List<DocumentEntity> dbEntities = List.of(
                    new DocumentEntity(2L, "Doc B", Instant.parse("2026-01-02T00:00:00Z")),
                    new DocumentEntity(1L, "Doc A", Instant.parse("2026-01-01T00:00:00Z"))
            );
            ScrollPosition posDb0 = ScrollPosition.backward(Map.of("id", 2L));
            ScrollPosition posDb1 = ScrollPosition.backward(Map.of("id", 1L));

            Window<DocumentEntity> window = mock(Window.class);
            when(window.getContent()).thenReturn(dbEntities);
            when(window.positionAt(0)).thenReturn(posDb0);
            when(window.positionAt(1)).thenReturn(posDb1);
            when(window.hasNext()).thenReturn(false); // No more backward records (reached first page)

            when(documentRepository.findBy(any(Specification.class), any(Function.class)))
                    .thenAnswer(invocation -> {
                        Function<JpaSpecificationExecutor.SpecificationFluentQuery<DocumentEntity>, Window<DocumentEntity>> queryFn = invocation.getArgument(1);
                        JpaSpecificationExecutor.SpecificationFluentQuery<DocumentEntity> query = mock(JpaSpecificationExecutor.SpecificationFluentQuery.class);
                        when(query.limit(2)).thenReturn(query);
                        when(query.sortBy(any(Sort.class))).thenReturn(query);
                        when(query.scroll(any(ScrollPosition.class))).thenReturn(window);
                        return queryFn.apply(query);
                    });

            String backwardScrollId = ScrollPositionCodec.encode(posDb0, ScrollDirection.BACKWARD);

            ScrollResponse<DocumentEntity> response = Scroll.scroll(
                    documentRepository,
                    backwardScrollId,
                    2,
                    "id",
                    "asc",
                    "backward",
                    Set.of("id")
            );

            // Verified content is reversed back to ascending natural order
            assertThat(response.contents()).containsExactly(
                    new DocumentEntity(1L, "Doc A", Instant.parse("2026-01-01T00:00:00Z")),
                    new DocumentEntity(2L, "Doc B", Instant.parse("2026-01-02T00:00:00Z"))
            );
            assertThat(response.hasNext()).isTrue();
            assertThat(response.nextScrollId()).isNotNull();
            assertThat(response.hasPrevious()).isFalse();
            assertThat(response.prevScrollId()).isNull();

            ScrollPositionCodec.DecodedScrollPosition nextDecoded = ScrollPositionCodec.decode(response.nextScrollId());
            assertThat(nextDecoded.direction()).isEqualTo(ScrollDirection.FORWARD);
            assertThat(((KeysetScrollPosition) nextDecoded.position()).getKeys()).containsEntry("id", 2L);
        }

        @Test
        @DisplayName("Backward scroll with more previous records should set prevScrollId")
        @SuppressWarnings("unchecked")
        void shouldSetPrevScrollIdWhenBackwardHasNext() {
            List<DocumentEntity> dbEntities = List.of(
                    new DocumentEntity(4L, "Doc D", Instant.parse("2026-01-04T00:00:00Z")),
                    new DocumentEntity(3L, "Doc C", Instant.parse("2026-01-03T00:00:00Z"))
            );
            ScrollPosition posDb0 = ScrollPosition.backward(Map.of("id", 4L));
            ScrollPosition posDb1 = ScrollPosition.backward(Map.of("id", 3L));

            Window<DocumentEntity> window = mock(Window.class);
            when(window.getContent()).thenReturn(dbEntities);
            when(window.positionAt(0)).thenReturn(posDb0);
            when(window.positionAt(1)).thenReturn(posDb1);
            when(window.hasNext()).thenReturn(true); // More backward pages available

            when(documentRepository.findBy(any(Specification.class), any(Function.class)))
                    .thenAnswer(invocation -> {
                        Function<JpaSpecificationExecutor.SpecificationFluentQuery<DocumentEntity>, Window<DocumentEntity>> queryFn = invocation.getArgument(1);
                        JpaSpecificationExecutor.SpecificationFluentQuery<DocumentEntity> query = mock(JpaSpecificationExecutor.SpecificationFluentQuery.class);
                        when(query.limit(2)).thenReturn(query);
                        when(query.sortBy(any(Sort.class))).thenReturn(query);
                        when(query.scroll(any(ScrollPosition.class))).thenReturn(window);
                        return queryFn.apply(query);
                    });

            String backwardScrollId = ScrollPositionCodec.encode(posDb0, ScrollDirection.BACKWARD);

            ScrollResponse<DocumentEntity> response = Scroll.scroll(
                    documentRepository,
                    backwardScrollId,
                    2,
                    "id",
                    "asc",
                    "backward",
                    Set.of("id")
            );

            assertThat(response.hasPrevious()).isTrue();
            assertThat(response.prevScrollId()).isNotNull();

            ScrollPositionCodec.DecodedScrollPosition prevDecoded = ScrollPositionCodec.decode(response.prevScrollId());
            assertThat(prevDecoded.direction()).isEqualTo(ScrollDirection.BACKWARD);
            assertThat(((KeysetScrollPosition) prevDecoded.position()).getKeys()).containsEntry("id", 3L);
        }

        @Test
        @DisplayName("Empty window should return empty contents without cursors")
        @SuppressWarnings("unchecked")
        void shouldHandleEmptyWindow() {
            Window<DocumentEntity> window = mock(Window.class);
            when(window.getContent()).thenReturn(Collections.emptyList());

            when(documentRepository.findBy(any(Specification.class), any(Function.class)))
                    .thenAnswer(invocation -> {
                        Function<JpaSpecificationExecutor.SpecificationFluentQuery<DocumentEntity>, Window<DocumentEntity>> queryFn = invocation.getArgument(1);
                        JpaSpecificationExecutor.SpecificationFluentQuery<DocumentEntity> query = mock(JpaSpecificationExecutor.SpecificationFluentQuery.class);
                        when(query.limit(10)).thenReturn(query);
                        when(query.sortBy(any(Sort.class))).thenReturn(query);
                        when(query.scroll(any(ScrollPosition.class))).thenReturn(window);
                        return queryFn.apply(query);
                    });

            ScrollResponse<DocumentDto> response = Scroll.scroll(
                    documentRepository,
                    null,
                    null,
                    10,
                    "id",
                    "desc",
                    "forward",
                    Set.of("id"),
                    entity -> new DocumentDto(entity.id(), entity.title())
            );

            assertThat(response.contents()).isEmpty();
            assertThat(response.isEmpty()).isTrue();
            assertThat(response.numberOfElements()).isZero();
            assertThat(response.hasNext()).isFalse();
            assertThat(response.hasPrevious()).isFalse();
            assertThat(response.nextScrollId()).isNull();
            assertThat(response.prevScrollId()).isNull();
            assertThat(response.pageSize()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should support Specification overload with non-null specification")
        @SuppressWarnings("unchecked")
        void shouldSupportSpecificationOverload() {
            Specification<DocumentEntity> spec = mock(Specification.class);
            Window<DocumentEntity> window = mock(Window.class);
            when(window.getContent()).thenReturn(Collections.emptyList());

            when(documentRepository.findBy(eq(spec), any(Function.class)))
                    .thenAnswer(invocation -> {
                        Function<JpaSpecificationExecutor.SpecificationFluentQuery<DocumentEntity>, Window<DocumentEntity>> queryFn = invocation.getArgument(1);
                        JpaSpecificationExecutor.SpecificationFluentQuery<DocumentEntity> query = mock(JpaSpecificationExecutor.SpecificationFluentQuery.class);
                        when(query.limit(5)).thenReturn(query);
                        when(query.sortBy(any(Sort.class))).thenReturn(query);
                        when(query.scroll(any(ScrollPosition.class))).thenReturn(window);
                        return queryFn.apply(query);
                    });

            ScrollResponse<DocumentEntity> response = Scroll.scroll(
                    documentRepository,
                    spec,
                    null,
                    5,
                    "id",
                    "asc",
                    "forward",
                    List.of("id")
            );

            assertThat(response).isNotNull();
            verify(documentRepository).findBy(eq(spec), any(Function.class));
        }

        @Test
        @DisplayName("Should support Sort object overload with ScrollDirection enum")
        @SuppressWarnings("unchecked")
        void shouldSupportSortObjectOverload() {
            Window<DocumentEntity> window = mock(Window.class);
            when(window.getContent()).thenReturn(Collections.emptyList());

            when(documentRepository.findBy(any(Specification.class), any(Function.class)))
                    .thenAnswer(invocation -> {
                        Function<JpaSpecificationExecutor.SpecificationFluentQuery<DocumentEntity>, Window<DocumentEntity>> queryFn = invocation.getArgument(1);
                        JpaSpecificationExecutor.SpecificationFluentQuery<DocumentEntity> query = mock(JpaSpecificationExecutor.SpecificationFluentQuery.class);
                        when(query.limit(15)).thenReturn(query);
                        when(query.sortBy(any(Sort.class))).thenReturn(query);
                        when(query.scroll(any(ScrollPosition.class))).thenReturn(window);
                        return queryFn.apply(query);
                    });

            Sort sort = Sort.by(Sort.Direction.ASC, "createdat");
            ScrollResponse<DocumentEntity> response = Scroll.scroll(
                    documentRepository,
                    null,
                    null,
                    15,
                    sort,
                    null, // Should default to FORWARD
                    List.of("createdAt"),
                    Function.identity()
            );

            assertThat(response).isNotNull();
            assertThat(response.pageSize()).isEqualTo(15);
        }

        @Test
        @DisplayName("Should support unmapped Sort object overload")
        @SuppressWarnings("unchecked")
        void shouldSupportUnmappedSortObjectOverload() {
            Window<DocumentEntity> window = mock(Window.class);
            when(window.getContent()).thenReturn(Collections.emptyList());

            when(documentRepository.findBy(any(Specification.class), any(Function.class)))
                    .thenAnswer(invocation -> {
                        Function<JpaSpecificationExecutor.SpecificationFluentQuery<DocumentEntity>, Window<DocumentEntity>> queryFn = invocation.getArgument(1);
                        JpaSpecificationExecutor.SpecificationFluentQuery<DocumentEntity> query = mock(JpaSpecificationExecutor.SpecificationFluentQuery.class);
                        when(query.limit(10)).thenReturn(query);
                        when(query.sortBy(any(Sort.class))).thenReturn(query);
                        when(query.scroll(any(ScrollPosition.class))).thenReturn(window);
                        return queryFn.apply(query);
                    });

            Sort sort = Sort.by(Sort.Direction.DESC, "id");
            ScrollResponse<DocumentEntity> response = Scroll.scroll(
                    documentRepository,
                    null,
                    null,
                    10,
                    sort,
                    ScrollDirection.FORWARD,
                    List.of("id")
            );

            assertThat(response).isNotNull();
        }

        @Test
        @DisplayName("Should support unmapped without specification overload")
        @SuppressWarnings("unchecked")
        void shouldSupportUnmappedWithoutSpecOverload() {
            Window<DocumentEntity> window = mock(Window.class);
            when(window.getContent()).thenReturn(Collections.emptyList());

            when(documentRepository.findBy(any(Specification.class), any(Function.class)))
                    .thenAnswer(invocation -> {
                        Function<JpaSpecificationExecutor.SpecificationFluentQuery<DocumentEntity>, Window<DocumentEntity>> queryFn = invocation.getArgument(1);
                        JpaSpecificationExecutor.SpecificationFluentQuery<DocumentEntity> query = mock(JpaSpecificationExecutor.SpecificationFluentQuery.class);
                        when(query.limit(10)).thenReturn(query);
                        when(query.sortBy(any(Sort.class))).thenReturn(query);
                        when(query.scroll(any(ScrollPosition.class))).thenReturn(window);
                        return queryFn.apply(query);
                    });

            ScrollResponse<DocumentEntity> response = Scroll.scroll(
                    documentRepository,
                    null,
                    10,
                    "id",
                    "asc",
                    "forward",
                    List.of("id")
            );

            assertThat(response).isNotNull();
        }
    }

    @Nested
    @DisplayName("Validation and Exception Handling")
    class ValidationAndExceptionTests {

        @Test
        @DisplayName("Backward scrolling on initial request should throw InvalidScrollingException")
        void backwardOnInitialRequestShouldThrowException() {
            assertThatThrownBy(() -> Scroll.scroll(
                    documentRepository, null, 10, "id", "asc", "backward", List.of("id"), Function.identity()))
                    .isInstanceOf(InvalidScrollingException.class)
                    .hasMessage("Backward scrolling requires a scrollId");

            assertThatThrownBy(() -> Scroll.scroll(
                    documentRepository, "   ", 10, "id", "asc", "backward", List.of("id"), Function.identity()))
                    .isInstanceOf(InvalidScrollingException.class)
                    .hasMessage("Backward scrolling requires a scrollId");
        }

        @Test
        @DisplayName("Mismatched scrollId direction and requested direction should throw InvalidScrollingException")
        void mismatchedDirectionShouldThrowException() {
            ScrollPosition pos = ScrollPosition.forward(Map.of("id", 10L));
            String forwardScrollId = ScrollPositionCodec.encode(pos, ScrollDirection.FORWARD);

            assertThatThrownBy(() -> Scroll.scroll(
                    documentRepository, forwardScrollId, 10, "id", "asc", "backward", List.of("id"), Function.identity()))
                    .isInstanceOf(InvalidScrollingException.class)
                    .hasMessage("scrollId direction does not match scrollDirection");
        }

        @Test
        @DisplayName("Null repository should throw IllegalArgumentException")
        void nullRepositoryShouldThrowException() {
            assertThatThrownBy(() -> Scroll.scroll(
                    null, null, 10, "id", "asc", "forward", List.of("id"), Function.identity()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Repository must not be null");
        }

        @Test
        @DisplayName("Null mapper should throw IllegalArgumentException")
        void nullMapperShouldThrowException() {
            assertThatThrownBy(() -> Scroll.scroll(
                    documentRepository, null, 10, "id", "asc", "forward", List.of("id"), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Mapper must not be null");
        }

        @Test
        @DisplayName("Page size below minimum should throw IllegalArgumentException")
        void underMinimumPageSizeShouldThrowException() {
            assertThatThrownBy(() -> Scroll.scroll(
                    documentRepository, null, 0, "id", "asc", "forward", List.of("id"), Function.identity()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Page size must be between 1 and 100");
        }

        @Test
        @DisplayName("Page size above maximum should throw IllegalArgumentException")
        void overMaximumPageSizeShouldThrowException() {
            assertThatThrownBy(() -> Scroll.scroll(
                    documentRepository, null, 101, "id", "asc", "forward", List.of("id"), Function.identity()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Page size must be between 1 and 100");
        }

        @Test
        @DisplayName("Invalid scroll direction should throw InvalidScrollingException")
        void invalidScrollDirectionShouldThrowException() {
            assertThatThrownBy(() -> Scroll.scroll(
                    documentRepository, null, 10, "id", "asc", "diagonal", List.of("id"), Function.identity()))
                    .isInstanceOf(InvalidScrollingException.class)
                    .hasMessage("Scroll direction must be 'forward' or 'backward'");
        }

        @Test
        @DisplayName("Null or blank scroll direction should default to FORWARD")
        @SuppressWarnings("unchecked")
        void nullOrBlankScrollDirectionShouldDefaultToForward() {
            Window<DocumentEntity> window = mock(Window.class);
            when(window.getContent()).thenReturn(Collections.emptyList());

            when(documentRepository.findBy(any(Specification.class), any(Function.class)))
                    .thenAnswer(invocation -> {
                        Function<JpaSpecificationExecutor.SpecificationFluentQuery<DocumentEntity>, Window<DocumentEntity>> queryFn = invocation.getArgument(1);
                        JpaSpecificationExecutor.SpecificationFluentQuery<DocumentEntity> query = mock(JpaSpecificationExecutor.SpecificationFluentQuery.class);
                        when(query.limit(10)).thenReturn(query);
                        when(query.sortBy(any(Sort.class))).thenReturn(query);
                        when(query.scroll(any(ScrollPosition.class))).thenReturn(window);
                        return queryFn.apply(query);
                    });

            ScrollResponse<DocumentEntity> res1 = Scroll.scroll(documentRepository, null, 10, "id", "asc", null, List.of("id"), Function.identity());
            ScrollResponse<DocumentEntity> res2 = Scroll.scroll(documentRepository, null, 10, "id", "asc", "   ", List.of("id"), Function.identity());
            assertThat(res1).isNotNull();
            assertThat(res2).isNotNull();
        }

        @Test
        @DisplayName("Invalid sort direction should throw IllegalArgumentException")
        void invalidSortDirectionShouldThrowException() {
            assertThatThrownBy(() -> Scroll.scroll(
                    documentRepository, null, 10, "id", "random", "forward", List.of("id"), Function.identity()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Sort direction must be 'asc' or 'desc'");
        }

        @Test
        @DisplayName("Null or blank sort direction should default to ASC")
        @SuppressWarnings("unchecked")
        void nullOrBlankSortDirectionShouldDefaultToAsc() {
            Window<DocumentEntity> window = mock(Window.class);
            when(window.getContent()).thenReturn(Collections.emptyList());

            when(documentRepository.findBy(any(Specification.class), any(Function.class)))
                    .thenAnswer(invocation -> {
                        Function<JpaSpecificationExecutor.SpecificationFluentQuery<DocumentEntity>, Window<DocumentEntity>> queryFn = invocation.getArgument(1);
                        JpaSpecificationExecutor.SpecificationFluentQuery<DocumentEntity> query = mock(JpaSpecificationExecutor.SpecificationFluentQuery.class);
                        when(query.limit(10)).thenReturn(query);
                        when(query.sortBy(any(Sort.class))).thenReturn(query);
                        when(query.scroll(any(ScrollPosition.class))).thenReturn(window);
                        return queryFn.apply(query);
                    });

            ScrollResponse<DocumentEntity> res1 = Scroll.scroll(documentRepository, null, 10, "id", null, "forward", List.of("id"), Function.identity());
            ScrollResponse<DocumentEntity> res2 = Scroll.scroll(documentRepository, null, 10, "id", "   ", "forward", List.of("id"), Function.identity());
            assertThat(res1).isNotNull();
            assertThat(res2).isNotNull();
        }

        @Test
        @DisplayName("Unsupported sort property should throw IllegalArgumentException")
        void unsupportedSortPropertyShouldThrowException() {
            assertThatThrownBy(() -> Scroll.scroll(
                    documentRepository, null, 10, "secretField", "asc", "forward", List.of("id"), Function.identity()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Unsupported sort property: secretField");
        }

        @Test
        @DisplayName("Sort requested when allowed properties is empty should throw IllegalArgumentException")
        void sortRequestedWhenAllowedEmptyShouldThrowException() {
            assertThatThrownBy(() -> Scroll.scroll(
                    documentRepository, null, 10, "id", "asc", "forward", Collections.emptyList(), Function.identity()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Sorting is not supported for this query");

            assertThatThrownBy(() -> Scroll.scroll(
                    documentRepository, null, 10, "id", "asc", "forward", null, Function.identity()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Sorting is not supported for this query");
        }

        @Test
        @DisplayName("Null or blank sort property should throw IllegalArgumentException")
        void nullOrBlankSortByShouldThrowException() {
            assertThatThrownBy(() -> Scroll.scroll(
                    documentRepository, null, 10, null, "asc", "forward", List.of("id"), Function.identity()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Sort property must not be null or blank");

            assertThatThrownBy(() -> Scroll.scroll(
                    documentRepository, null, 10, "   ", "asc", "forward", List.of("id"), Function.identity()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Sort property must not be null or blank");
        }

        @Test
        @DisplayName("Null or blank element in allowedSortProperties should throw IllegalArgumentException")
        void nullOrBlankInAllowedSortPropertiesShouldThrowException() {
            assertThatThrownBy(() -> Scroll.scroll(
                    documentRepository, null, 10, "id", "asc", "forward", Collections.singletonList(null), Function.identity()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Allowed sort properties must not be null or blank");

            assertThatThrownBy(() -> Scroll.scroll(
                    documentRepository, null, 10, "id", "asc", "forward", List.of("id", "  "), Function.identity()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Allowed sort properties must not be null or blank");
        }

        @Test
        @DisplayName("Null or unsorted Sort object in Sort overload should throw IllegalArgumentException")
        void nullOrUnsortedSortObjectShouldThrowException() {
            assertThatThrownBy(() -> Scroll.scroll(
                    documentRepository, null, null, 10, null, ScrollDirection.FORWARD, List.of("id"), Function.identity()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Sort must not be null");

            assertThatThrownBy(() -> Scroll.scroll(
                    documentRepository, null, null, 10, Sort.unsorted(), ScrollDirection.FORWARD, List.of("id"), Function.identity()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Sorting is required for keyset scrolling");
        }
    }

    @Nested
    @DisplayName("ScrollPositionCodec Codec & Type Safety")
    class CodecTests {

        @Test
        @DisplayName("Private constructor should throw UnsupportedOperationException")
        void codecPrivateConstructorShouldThrowException() throws Exception {
            Constructor<ScrollPositionCodec> constructor = ScrollPositionCodec.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            assertThatThrownBy(constructor::newInstance)
                    .isInstanceOf(InvocationTargetException.class)
                    .hasCauseInstanceOf(UnsupportedOperationException.class)
                    .hasRootCauseMessage("ScrollPositionCodec is a utility class and cannot be instantiated");
        }

        @Test
        @DisplayName("Should return null when encoding null, initial, or non-keyset position")
        void shouldReturnNullWhenEncodingInvalidPositions() {
            assertThat(ScrollPositionCodec.encode(null, ScrollDirection.FORWARD)).isNull();
            assertThat(ScrollPositionCodec.encode(ScrollPosition.keyset(), ScrollDirection.FORWARD)).isNull();
            assertThat(ScrollPositionCodec.encode(ScrollPosition.offset(10), ScrollDirection.FORWARD)).isNull();
        }

        @Test
        @DisplayName("Should encode and decode all supported Java types losslessly")
        void shouldEncodeAndDecodeAllSupportedTypes() {
            UUID testUuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
            Instant testInstant = Instant.parse("2026-10-17T20:30:00Z");
            LocalDate testDate = LocalDate.parse("2026-10-17");
            LocalDateTime testDateTime = LocalDateTime.parse("2026-10-17T20:30:00");
            OffsetDateTime testOffsetDateTime = OffsetDateTime.of(testDateTime, ZoneOffset.UTC);
            ZonedDateTime testZonedDateTime = ZonedDateTime.of(testDateTime, ZoneOffset.UTC);

            Map<String, Object> keys = Map.ofEntries(
                    Map.entry("id", 100L),
                    Map.entry("count", 42),
                    Map.entry("shortVal", (short) 7),
                    Map.entry("byteVal", (byte) 2),
                    Map.entry("uuid", testUuid),
                    Map.entry("created", testInstant),
                    Map.entry("score", 99.5d),
                    Map.entry("rate", 12.5f),
                    Map.entry("amount", new BigDecimal("1234.56")),
                    Map.entry("bigInt", new BigInteger("9999999999")),
                    Map.entry("active", true),
                    Map.entry("date", testDate),
                    Map.entry("dateTime", testDateTime),
                    Map.entry("offsetDateTime", testOffsetDateTime),
                    Map.entry("zonedDateTime", testZonedDateTime),
                    Map.entry("specialString", "title;with=equal%and spaces")
            );

            ScrollPosition position = ScrollPosition.forward(keys);
            String encoded = ScrollPositionCodec.encode(position, ScrollDirection.FORWARD);
            assertThat(encoded).isNotNull().isNotBlank();

            ScrollPositionCodec.DecodedScrollPosition decoded = ScrollPositionCodec.decode(encoded);
            assertThat(decoded.direction()).isEqualTo(ScrollDirection.FORWARD);

            KeysetScrollPosition keyset = (KeysetScrollPosition) decoded.position();
            assertThat(keyset.getKeys()).containsEntry("id", 100L);
            assertThat(keyset.getKeys()).containsEntry("count", 42);
            assertThat(keyset.getKeys()).containsEntry("shortVal", (short) 7);
            assertThat(keyset.getKeys()).containsEntry("byteVal", (byte) 2);
            assertThat(keyset.getKeys()).containsEntry("uuid", testUuid);
            assertThat(keyset.getKeys()).containsEntry("created", testInstant);
            assertThat(keyset.getKeys()).containsEntry("score", 99.5d);
            assertThat(keyset.getKeys()).containsEntry("rate", 12.5f);
            assertThat(keyset.getKeys()).containsEntry("amount", new BigDecimal("1234.56"));
            assertThat(keyset.getKeys()).containsEntry("bigInt", new BigInteger("9999999999"));
            assertThat(keyset.getKeys()).containsEntry("active", true);
            assertThat(keyset.getKeys()).containsEntry("date", testDate);
            assertThat(keyset.getKeys()).containsEntry("dateTime", testDateTime);
            assertThat(keyset.getKeys()).containsEntry("offsetDateTime", testOffsetDateTime);
            assertThat(keyset.getKeys()).containsEntry("zonedDateTime", testZonedDateTime);
            assertThat(keyset.getKeys()).containsEntry("specialString", "title;with=equal%and spaces");
        }

        @Test
        @DisplayName("Should encode and decode backward direction")
        void shouldEncodeAndDecodeBackwardDirection() {
            ScrollPosition position = ScrollPosition.backward(Map.of("id", 50L));
            String encoded = ScrollPositionCodec.encode(position, ScrollDirection.BACKWARD);

            ScrollPositionCodec.DecodedScrollPosition decoded = ScrollPositionCodec.decode(encoded);
            assertThat(decoded.direction()).isEqualTo(ScrollDirection.BACKWARD);
            assertThat(((KeysetScrollPosition) decoded.position()).getKeys()).containsEntry("id", 50L);
        }

        @Test
        @DisplayName("Should decode null or blank tokens to initial keyset position")
        void shouldDecodeNullOrBlankToInitialKeyset() {
            ScrollPositionCodec.DecodedScrollPosition dec1 = ScrollPositionCodec.decode(null);
            assertThat(dec1.position().isInitial()).isTrue();
            assertThat(dec1.direction()).isEqualTo(ScrollDirection.FORWARD);

            ScrollPositionCodec.DecodedScrollPosition dec2 = ScrollPositionCodec.decode("   ");
            assertThat(dec2.position().isInitial()).isTrue();
            assertThat(dec2.direction()).isEqualTo(ScrollDirection.FORWARD);
        }

        @Test
        @DisplayName("Should throw InvalidScrollingException on corrupted base64 or invalid format")
        void shouldThrowOnCorruptedToken() {
            assertThatThrownBy(() -> ScrollPositionCodec.decode("!!!not-valid-base64???"))
                    .isInstanceOf(InvalidScrollingException.class)
                    .hasMessageStartingWith("Invalid scrollId:");
        }

        @Test
        @DisplayName("Should parse legacy un-prefixed values gracefully")
        void shouldParseLegacyUnprefixedValues() {
            UUID uuid = UUID.randomUUID();
            String legacyToken = "direction=FORWARD;id=123;uuid=" + uuid + ";title=MyTitle";
            String base64 = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(legacyToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            ScrollPositionCodec.DecodedScrollPosition decoded = ScrollPositionCodec.decode(base64);
            KeysetScrollPosition keyset = (KeysetScrollPosition) decoded.position();
            assertThat(keyset.getKeys()).containsEntry("id", 123L);
            assertThat(keyset.getKeys()).containsEntry("uuid", uuid);
            assertThat(keyset.getKeys()).containsEntry("title", "MyTitle");
        }

        @Test
        @DisplayName("Should verify ScrollDirection enum methods and reversal")
        void shouldVerifyScrollDirectionMethods() {
            assertThat(ScrollDirection.from(null)).isEqualTo(ScrollDirection.FORWARD);
            assertThat(ScrollDirection.from("")).isEqualTo(ScrollDirection.FORWARD);
            assertThat(ScrollDirection.from("  ")).isEqualTo(ScrollDirection.FORWARD);
            assertThat(ScrollDirection.from("FoRwArD")).isEqualTo(ScrollDirection.FORWARD);
            assertThat(ScrollDirection.from("BaCkWaRd")).isEqualTo(ScrollDirection.BACKWARD);
            assertThat(ScrollDirection.FORWARD.reverse()).isEqualTo(ScrollDirection.BACKWARD);
            assertThat(ScrollDirection.BACKWARD.reverse()).isEqualTo(ScrollDirection.FORWARD);

            assertThatThrownBy(() -> ScrollDirection.from("invalid"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Scroll direction must be 'forward' or 'backward'");
        }

        @Test
        @DisplayName("Should verify DecodedScrollPosition builder")
        void shouldVerifyDecodedScrollPositionBuilder() {
            ScrollPosition pos = ScrollPosition.forward(Map.of("id", 1L));
            ScrollPositionCodec.DecodedScrollPosition decoded = ScrollPositionCodec.DecodedScrollPosition.builder()
                    .position(pos)
                    .direction(ScrollDirection.BACKWARD)
                    .build();

            assertThat(decoded.position()).isEqualTo(pos);
            assertThat(decoded.direction()).isEqualTo(ScrollDirection.BACKWARD);
        }

        @Test
        @DisplayName("Should default direction to FORWARD when direction is null in encode")
        void shouldDefaultToForwardWhenDirectionIsNullInEncode() {
            ScrollPosition position = ScrollPosition.forward(Map.of("id", 42L));
            String encoded = ScrollPositionCodec.encode(position, null);
            assertThat(encoded).isNotNull();

            ScrollPositionCodec.DecodedScrollPosition decoded = ScrollPositionCodec.decode(encoded);
            assertThat(decoded.direction()).isEqualTo(ScrollDirection.FORWARD);
            assertThat(((KeysetScrollPosition) decoded.position()).getKeys()).containsEntry("id", 42L);
        }

        @Test
        @DisplayName("Should handle null key values in encode and decode")
        void shouldHandleNullKeyValues() {
            java.util.Map<String, Object> mapWithNull = new java.util.HashMap<>();
            mapWithNull.put("id", 42L);
            mapWithNull.put("nullField", null);

            ScrollPosition position = ScrollPosition.forward(mapWithNull);
            String encoded = ScrollPositionCodec.encode(position, ScrollDirection.FORWARD);
            assertThat(encoded).isNotNull();

            ScrollPositionCodec.DecodedScrollPosition decoded = ScrollPositionCodec.decode(encoded);
            KeysetScrollPosition keyset = (KeysetScrollPosition) decoded.position();
            assertThat(keyset.getKeys()).containsKey("nullField");
            assertThat(keyset.getKeys().get("nullField")).isNull();
        }

        @Test
        @DisplayName("Should ignore malformed pairs and fallback to FORWARD on unrecognized direction")
        void shouldIgnoreMalformedPairsAndFallbackDirection() {
            String token = "direction=UNKNOWN_DIRECTION;malformedPairWithoutEquals;id=L:99";
            String base64 = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            ScrollPositionCodec.DecodedScrollPosition decoded = ScrollPositionCodec.decode(base64);
            assertThat(decoded.direction()).isEqualTo(ScrollDirection.FORWARD);
            KeysetScrollPosition keyset = (KeysetScrollPosition) decoded.position();
            assertThat(keyset.getKeys()).containsEntry("id", 99L);
        }
    }

    @Nested
    @DisplayName("ScrollResponse Wrapper Functionality")
    class ScrollResponseTests {

        @Test
        @DisplayName("Should create immutable response with valid parameters")
        void shouldCreateImmutableResponse() {
            List<String> items = List.of("Alpha", "Beta");
            ScrollResponse<String> response = new ScrollResponse<>(
                    items, "next_123", "prev_123", true, true, 10);

            assertThat(response.contents()).containsExactly("Alpha", "Beta");
            assertThat(response.hasNext()).isTrue();
            assertThat(response.hasPrevious()).isTrue();
            assertThat(response.nextScrollId()).isEqualTo("next_123");
            assertThat(response.prevScrollId()).isEqualTo("prev_123");
            assertThat(response.pageSize()).isEqualTo(10);
            assertThat(response.numberOfElements()).isEqualTo(2);
            assertThat(response.isEmpty()).isFalse();

            assertThatThrownBy(() -> response.contents().add("disallowed"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Should handle null contents by defaulting to empty list")
        void shouldHandleNullContents() {
            ScrollResponse<String> response = new ScrollResponse<>(
                    null, null, null, false, false, 5);
            assertThat(response.contents()).isEmpty();
            assertThat(response.isEmpty()).isTrue();
            assertThat(response.numberOfElements()).isZero();
        }

        @Test
        @DisplayName("Should validate numeric and cursor invariants in constructor")
        void shouldValidateInvariantsInConstructor() {
            assertThatThrownBy(() -> new ScrollResponse<>(List.of(), null, null, false, false, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Page size must be >= 0");

            assertThatThrownBy(() -> new ScrollResponse<>(List.of(), "next", null, false, false, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Next scroll ID must be provided exactly when hasNext is true");

            assertThatThrownBy(() -> new ScrollResponse<>(List.of(), null, "prev", false, false, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Previous scroll ID must be provided exactly when hasPrevious is true");

            assertThatThrownBy(() -> new ScrollResponse<>(List.of(), null, null, true, false, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Next scroll ID must be provided exactly when hasNext is true");

            assertThatThrownBy(() -> new ScrollResponse<>(List.of(), null, null, false, true, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Previous scroll ID must be provided exactly when hasPrevious is true");
        }

        @Test
        @DisplayName("Should support ScrollResponse.from factory methods with Window")
        @SuppressWarnings("unchecked")
        void shouldSupportFromFactoryMethods() {
            Window<DocumentEntity> window = mock(Window.class);
            when(window.getContent()).thenReturn(List.of(new DocumentEntity(1L, "Doc", Instant.now())));
            when(window.hasNext()).thenReturn(true);
            when(window.size()).thenReturn(1);

            ScrollResponse<DocumentDto> res1 = ScrollResponse.from(
                    window,
                    entity -> new DocumentDto(entity.id(), entity.title()),
                    "prev_id",
                    "next_id",
                    10
            );
            assertThat(res1.contents()).hasSize(1);
            assertThat(res1.hasNext()).isTrue();
            assertThat(res1.hasPrevious()).isTrue();
            assertThat(res1.pageSize()).isEqualTo(10);

            ScrollResponse<DocumentDto> res2 = ScrollResponse.from(
                    window,
                    entity -> new DocumentDto(entity.id(), entity.title()),
                    "prev_id",
                    "next_id"
            );
            assertThat(res2.pageSize()).isEqualTo(1);

            ScrollResponse<DocumentEntity> res3 = ScrollResponse.from(window, "prev_id", "next_id", 10);
            assertThat(res3.contents().getFirst().title()).isEqualTo("Doc");

            ScrollResponse<DocumentEntity> res4 = ScrollResponse.from(window, "prev_id", "next_id");
            assertThat(res4.contents().getFirst().title()).isEqualTo("Doc");
        }

        @Test
        @DisplayName("ScrollResponse.from should reject null window or mapper")
        void fromShouldRejectNullWindowOrMapper() {
            assertThatThrownBy(() -> ScrollResponse.from(null, "p", "n"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Window must not be null");

            Window<String> window = Window.from(List.of("test"), ScrollPosition::offset);
            assertThatThrownBy(() -> ScrollResponse.from(window, null, "p", "n"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Mapper must not be null");
        }

        @Test
        @DisplayName("Should build valid ScrollResponse using Builder")
        void shouldBuildUsingBuilder() {
            ScrollResponse<String> response = ScrollResponse.<String>builder()
                    .contents(List.of("A", "B"))
                    .nextScrollId("next")
                    .prevScrollId("prev")
                    .hasNext(true)
                    .hasPrevious(true)
                    .pageSize(10)
                    .build();

            assertThat(response.contents()).containsExactly("A", "B");
            assertThat(response.nextScrollId()).isEqualTo("next");
            assertThat(response.prevScrollId()).isEqualTo("prev");
            assertThat(response.hasNext()).isTrue();
            assertThat(response.hasPrevious()).isTrue();
            assertThat(response.pageSize()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should verify equals, hashCode, and toString")
        void shouldVerifyEqualsHashCodeAndToString() {
            ScrollResponse<String> res1 = new ScrollResponse<>(List.of("A"), "n", "p", true, true, 10);
            ScrollResponse<String> res2 = new ScrollResponse<>(List.of("A"), "n", "p", true, true, 10);
            ScrollResponse<String> res3 = new ScrollResponse<>(List.of("B"), "n", "p", true, true, 10);

            assertThat(res1).isEqualTo(res2);
            assertThat(res1.hashCode()).isEqualTo(res2.hashCode());
            assertThat(res1).isNotEqualTo(res3);
            assertThat(res1.toString()).contains("ScrollResponse", "A", "nextScrollId=n");
        }
    }

    @Nested
    @DisplayName("InvalidScrollingException Invariants")
    class ExceptionInvariantsTests {

        @Test
        @DisplayName("Should instantiate InvalidScrollingException with message and cause")
        void shouldInstantiateException() {
            InvalidScrollingException ex1 = new InvalidScrollingException("Error message");
            assertThat(ex1.getMessage()).isEqualTo("Error message");

            RuntimeException cause = new RuntimeException("Underlying cause");
            InvalidScrollingException ex2 = new InvalidScrollingException("Wrapped message", cause);
            assertThat(ex2.getMessage()).isEqualTo("Wrapped message");
            assertThat(ex2.getCause()).isEqualTo(cause);
        }
    }
}
