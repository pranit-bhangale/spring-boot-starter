package io.github.pranit.scroll;

import io.github.pranit.scroll.service.Scroll;
import io.github.pranit.scroll.wrapper.ScrollResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.query.FluentQuery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScrollTest {

    private static final Logger log = LoggerFactory.getLogger(ScrollTest.class);

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String CYAN = "\u001B[36m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";

    @Test
    @DisplayName("Simulate REST API bidirectional keyset scrolling and print colored JSON and table to console")
    @SuppressWarnings("unchecked")
    void shouldDisplayBidirectionalScrollApiResponseOnConsole() {
        List<DocumentMetadata> databaseRecords = IntStream.rangeClosed(1, 25)
                .mapToObj(i -> DocumentMetadata.builder()
                        .id((long) i)
                        .title("Spring Boot Keyset Guide #" + String.format("%02d", i))
                        .fileSize(i * 1024L)
                        .status()
                        .build())
                .toList();

        JpaSpecificationExecutor<DocumentMetadata> documentRepository = mock(JpaSpecificationExecutor.class);

        when(documentRepository.findBy(any(Specification.class), any(Function.class)))
                .thenAnswer(invocation -> {
                    Function<FluentQuery.FetchableFluentQuery<DocumentMetadata>, Window<DocumentMetadata>> queryFn =
                            invocation.getArgument(1);

                    JpaSpecificationExecutor.SpecificationFluentQuery<DocumentMetadata> query =
                            mock(JpaSpecificationExecutor.SpecificationFluentQuery.class);

                    final int[] capturedLimit = new int[]{10};
                    final ScrollPosition[] capturedPosition = new ScrollPosition[]{ScrollPosition.keyset()};

                    when(query.limit(any(Integer.class))).thenAnswer(limitInv -> {
                        capturedLimit[0] = limitInv.getArgument(0);
                        return query;
                    });
                    when(query.sortBy(any(Sort.class))).thenReturn(query);
                    when(query.scroll(any(ScrollPosition.class))).thenAnswer(scrollInv -> {
                        capturedPosition[0] = scrollInv.getArgument(0);

                        int limit = capturedLimit[0];
                        ScrollPosition position = capturedPosition[0];

                        List<DocumentMetadata> slice;
                        boolean hasMore;

                        if (position.isInitial()) {
                            slice = databaseRecords.stream().limit(limit).toList();
                            hasMore = databaseRecords.size() > limit;
                        } else if (position instanceof KeysetScrollPosition keyset) {
                            Long lastId = (Long) keyset.getKeys().get("id");
                            if (keyset.scrollsBackward()) {
                                List<DocumentMetadata> backwardCandidates = databaseRecords.stream()
                                        .filter(doc -> doc.id() < lastId)
                                        .sorted(Comparator.comparing(DocumentMetadata::id).reversed())
                                        .toList();
                                slice = backwardCandidates.stream().limit(limit).toList();
                                hasMore = backwardCandidates.size() > limit;
                            } else {
                                List<DocumentMetadata> forwardCandidates = databaseRecords.stream()
                                        .filter(doc -> doc.id() > lastId)
                                        .sorted(Comparator.comparing(DocumentMetadata::id))
                                        .toList();
                                slice = forwardCandidates.stream().limit(limit).toList();
                                hasMore = forwardCandidates.size() > limit;
                            }
                        } else {
                            slice = List.of();
                            hasMore = false;
                        }

                        Window<DocumentMetadata> window = mock(Window.class);
                        when(window.getContent()).thenReturn(new ArrayList<>(slice));
                        when(window.hasNext()).thenReturn(hasMore);
                        when(window.size()).thenReturn(slice.size());
                        when(window.positionAt(any(Integer.class))).thenAnswer(idxInv -> {
                            int idx = idxInv.getArgument(0);
                            DocumentMetadata doc = slice.get(idx);
                            return position instanceof KeysetScrollPosition keyset && keyset.scrollsBackward()
                                    ? ScrollPosition.backward(Map.of("id", doc.id()))
                                    : ScrollPosition.forward(Map.of("id", doc.id()));
                        });
                        return window;
                    });

                    return queryFn.apply(query);
                });

        Set<String> allowedSorts = Set.of("id", "title", "fileSize");

        // 1. Initial Forward Page (Items 1..5)
        ScrollResponse<DocumentResponse> page1 = Scroll.scroll(
                documentRepository,
                null,
                5,
                "id",
                "asc",
                "forward",
                allowedSorts,
                doc -> DocumentResponse.builder()
                        .id(doc.id())
                        .title(doc.title())
                        .fileSize(doc.fileSize())
                        .status(doc.status())
                        .build()
        );

        printScrollApiResponseConsole("GET /api/v1/documents?pageSize=5&sortBy=id&sortDirection=asc&scrollDirection=forward", page1);

        assertThat(page1.contents()).hasSize(5);
        assertThat(page1.contents().getFirst().id()).isEqualTo(1L);
        assertThat(page1.contents().getLast().id()).isEqualTo(5L);
        assertThat(page1.hasNext()).isTrue();
        assertThat(page1.hasPrevious()).isFalse();
        assertThat(page1.nextScrollId()).isNotNull();

        // 2. Second Forward Page (Items 6..10) using nextScrollId from page 1
        ScrollResponse<DocumentResponse> page2 = Scroll.scroll(
                documentRepository,
                page1.nextScrollId(),
                5,
                "id",
                "asc",
                "forward",
                allowedSorts,
                doc -> DocumentResponse.builder()
                        .id(doc.id())
                        .title(doc.title())
                        .fileSize(doc.fileSize())
                        .status(doc.status())
                        .build()
        );

        printScrollApiResponseConsole("GET /api/v1/documents?pageSize=5&sortBy=id&sortDirection=asc&scrollDirection=forward&scrollId=" + page1.nextScrollId(), page2);

        assertThat(page2.contents()).hasSize(5);
        assertThat(page2.contents().getFirst().id()).isEqualTo(6L);
        assertThat(page2.contents().getLast().id()).isEqualTo(10L);
        assertThat(page2.hasNext()).isTrue();
        assertThat(page2.hasPrevious()).isTrue();
        assertThat(page2.prevScrollId()).isNotNull();

        // 3. Backward Scroll from Page 2 using prevScrollId (returns Items 1..5 in natural ascending order!)
        ScrollResponse<DocumentResponse> backwardPage1 = Scroll.scroll(
                documentRepository,
                page2.prevScrollId(),
                5,
                "id",
                "asc",
                "backward",
                allowedSorts,
                doc -> DocumentResponse.builder()
                        .id(doc.id())
                        .title(doc.title())
                        .fileSize(doc.fileSize())
                        .status(doc.status())
                        .build()
        );

        printScrollApiResponseConsole("GET /api/v1/documents?pageSize=5&sortBy=id&sortDirection=asc&scrollDirection=backward&scrollId=" + page2.prevScrollId(), backwardPage1);

        assertThat(backwardPage1.contents()).hasSize(5);
        assertThat(backwardPage1.contents().getFirst().id()).isEqualTo(1L);
        assertThat(backwardPage1.contents().getLast().id()).isEqualTo(5L);
        assertThat(backwardPage1.hasNext()).isTrue();
        assertThat(backwardPage1.hasPrevious()).isFalse();
    }

    private void printScrollApiResponseConsole(String requestUrl, ScrollResponse<DocumentResponse> response) {
        String json = formatAsPrettyJson(response);
        StringBuilder output = new StringBuilder();
        output.append("\n")
                .append(CYAN).repeat("=", 80).append(RESET).append("\n")
                .append(BOLD).append("HTTP REQUEST  : ").append(RESET)
                .append(GREEN).append(requestUrl).append(RESET).append("\n")
                .append(BOLD).append("HTTP RESPONSE : ").append(RESET)
                .append(GREEN).append("200 OK").append(RESET).append("\n")
                .append(BOLD).append("CONTENT-TYPE  : ").append(RESET)
                .append(YELLOW).append("application/json").append(RESET).append("\n")
                .append(CYAN).repeat("-", 80).append(RESET).append("\n")
                .append(json).append("\n")
                .append(CYAN).repeat("-", 80).append(RESET).append("\n")
                .append(BOLD).append("TABLE VIEW:").append(RESET).append("\n")
                .append(String.format("| %-4s | %-32s | %-12s | %-8s |\n", "ID", "TITLE", "FILE SIZE", "STATUS"))
                .append("|").repeat("-", 6).append("|").repeat("-", 34).append("|").repeat("-", 14).append("|").repeat("-", 10).append("|\n");

        for (DocumentResponse item : response.contents()) {
            output.append(String.format("| %-4d | %-32s | %-12d | %-8s |\n", item.id(), item.title(), item.fileSize(), item.status()));
        }

        output.append(CYAN).repeat("-", 80).append(RESET).append("\n")
                .append(String.format("Scroll Window: Elements %d | PageSize %d | [HasNext: %b] [HasPrev: %b]\n",
                        response.numberOfElements(),
                        response.pageSize(),
                        response.hasNext(),
                        response.hasPrevious()))
                .append(String.format("Cursors      : Next [%s] | Prev [%s]\n",
                        response.nextScrollId() != null ? response.nextScrollId() : "none",
                        response.prevScrollId() != null ? response.prevScrollId() : "none"))
                .append(CYAN).repeat("=", 80).append(RESET);

        log.info("{}", output);
    }

    private String formatAsPrettyJson(ScrollResponse<DocumentResponse> response) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  ").append(CYAN).append("\"contents\"").append(RESET).append(": [\n");
        for (int i = 0; i < response.contents().size(); i++) {
            DocumentResponse item = response.contents().get(i);
            sb.append("    {\n");
            sb.append(String.format("      %s\"id\"%s: %s%d%s,\n", CYAN, RESET, YELLOW, item.id(), RESET));
            sb.append(String.format("      %s\"title\"%s: %s\"%s\"%s,\n", CYAN, RESET, GREEN, item.title(), RESET));
            sb.append(String.format("      %s\"fileSize\"%s: %s%d%s,\n", CYAN, RESET, YELLOW, item.fileSize(), RESET));
            sb.append(String.format("      %s\"status\"%s: %s\"%s\"%s\n", CYAN, RESET, GREEN, item.status(), RESET));
            sb.append("    }").append(i < response.contents().size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ],\n");
        sb.append(String.format("  %s\"hasNext\"%s: %s%b%s,\n", CYAN, RESET, BLUE, response.hasNext(), RESET));
        sb.append(String.format("  %s\"nextScrollId\"%s: %s\"%s\"%s,\n", CYAN, RESET, YELLOW, response.nextScrollId(), RESET));
        sb.append(String.format("  %s\"hasPrevious\"%s: %s%b%s,\n", CYAN, RESET, BLUE, response.hasPrevious(), RESET));
        sb.append(String.format("  %s\"prevScrollId\"%s: %s%s%s,\n", CYAN, RESET, YELLOW,
                response.prevScrollId() != null ? "\"" + response.prevScrollId() + "\"" : "null", RESET));
        sb.append(String.format("  %s\"pageSize\"%s: %s%d%s\n", CYAN, RESET, YELLOW, response.pageSize(), RESET));
        sb.append("}");
        return sb.toString();
    }

    private record DocumentMetadata(
            Long id,
            String title,
            Long fileSize,
            String status
    ) {
        static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private Long id;
            private String title;
            private Long fileSize;
            private String status;

            private Builder() {
            }

            Builder id(final Long id) {
                this.id = id;
                return this;
            }

            Builder title(final String title) {
                this.title = title;
                return this;
            }

            Builder fileSize(final Long fileSize) {
                this.fileSize = fileSize;
                return this;
            }

            Builder status() {
                this.status = "ACTIVE";
                return this;
            }

            DocumentMetadata build() {
                return new DocumentMetadata(id, title, fileSize, status);
            }
        }
    }

    private record DocumentResponse(
            Long id,
            String title,
            Long fileSize,
            String status
    ) {
        static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private Long id;
            private String title;
            private Long fileSize;
            private String status;

            private Builder() {
            }

            Builder id(final Long id) {
                this.id = id;
                return this;
            }

            Builder title(final String title) {
                this.title = title;
                return this;
            }

            Builder fileSize(final Long fileSize) {
                this.fileSize = fileSize;
                return this;
            }

            Builder status(final String status) {
                this.status = status;
                return this;
            }

            DocumentResponse build() {
                return new DocumentResponse(id, title, fileSize, status);
            }
        }
    }
}
