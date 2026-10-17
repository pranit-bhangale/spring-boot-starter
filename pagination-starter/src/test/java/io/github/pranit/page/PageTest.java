package io.github.pranit.page;

import io.github.pranit.page.service.Page;
import io.github.pranit.page.wrapper.PageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PageTest {

    private static final Logger log = LoggerFactory.getLogger(PageTest.class);

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String CYAN = "\u001B[36m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";

    @Test
    @DisplayName("Simulate REST API paginated response and print JSON to console")
    @SuppressWarnings("unchecked")
    void shouldDisplayPaginatedApiResponseOnConsole() {
        List<UserAccount> databaseRecords = IntStream.rangeClosed(1, 25)
                .mapToObj(i -> UserAccount.builder()
                        .id((long) i)
                        .username("user_" + String.format("%02d", i))
                        .email("user" + i + "@gmail.com")
                        .role(i % 5 == 0 ? "ADMIN" : "USER")
                        .status()
                        .build())
                .toList();

        JpaRepository<UserAccount, Long> userRepository = mock(JpaRepository.class);
        when(userRepository.findAll(any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(0);
                    Comparator<UserAccount> comparator = Comparator.comparing(UserAccount::username);
                    if (pageable.getSort().getOrderFor("username") != null &&
                            Objects.requireNonNull(pageable.getSort().getOrderFor("username"))
                                    .getDirection()
                                    .isDescending()) {
                        comparator = comparator.reversed();
                    }
                    List<UserAccount> sorted = databaseRecords
                            .stream()
                            .sorted(comparator)
                            .toList();
                    int start = (int) pageable.getOffset();
                    int end = Math.min(start + pageable.getPageSize(), sorted.size());
                    List<UserAccount> slice = (start <= end)
                            ? sorted.subList(start, end) : List.of();
                    return new PageImpl<>(slice, pageable, sorted.size());
                });

        Set<String> allowedSorts = Set.of("id", "username", "email");
        Pageable requestPage0 = PageRequest
                .of(0, 5, Sort.by(Sort.Direction.ASC, "username"));
        PageResponse<UserResponseDto> apiResponsePage0 = Page
                .paginate(userRepository, requestPage0, allowedSorts,
                        entity -> UserResponseDto.builder()
                                .id(entity.id())
                                .username(entity.username())
                                .email(entity.email())
                                .role(entity.role())
                                .build()
                );

        printApiResponseConsole
                ("GET /api/v1/users?page=0&size=5&sort=username,asc", apiResponsePage0);

        Pageable requestPage1 = PageRequest
                .of(1, 5, Sort.by(Sort.Direction.ASC, "username"));
        PageResponse<UserResponseDto> apiResponsePage1 = Page
                .paginate(userRepository, requestPage1, allowedSorts,
                        entity -> UserResponseDto.builder()
                                .id(entity.id())
                                .username(entity.username())
                                .email(entity.email())
                                .role(entity.role())
                                .build()
                );

        printApiResponseConsole
                ("GET /api/v1/users?page=1&size=5&sort=username,asc", apiResponsePage1);

        Pageable requestPageLast = PageRequest
                .of(4, 5, Sort.by(Sort.Direction.ASC, "username"));
        PageResponse<UserResponseDto> apiResponsePageLast = Page
                .paginate(userRepository, requestPageLast, allowedSorts,
                        entity -> UserResponseDto.builder()
                                .id(entity.id())
                                .username(entity.username())
                                .email(entity.email())
                                .role(entity.role())
                                .build()
                );

        printApiResponseConsole
                ("GET /api/v1/users?page=4&size=5&sort=username,asc", apiResponsePageLast);

        assertThat(apiResponsePage0.currentPage()).isZero();
        assertThat(apiResponsePage0.pageSize()).isEqualTo(5);
        assertThat(apiResponsePage0.totalElements()).isEqualTo(25);
        assertThat(apiResponsePage0.totalPages()).isEqualTo(5);
        assertThat(apiResponsePage0.isFirstPage()).isTrue();
        assertThat(apiResponsePage0.isLastPage()).isFalse();
        assertThat(apiResponsePage0.hasNext()).isTrue();
        assertThat(apiResponsePageLast.isFirstPage()).isFalse();
        assertThat(apiResponsePageLast.isLastPage()).isTrue();
        assertThat(apiResponsePageLast.hasNext()).isFalse();
    }

    private void printApiResponseConsole(String requestUrl, PageResponse<UserResponseDto> response) {
        String json = formatAsPrettyJson(response);
        StringBuilder output = new StringBuilder();
        output.append("\n")
                .append(CYAN).repeat("=", 80)
                .append(RESET).append("\n")
                .append(BOLD).append("HTTP REQUEST  : ")
                .append(RESET)
                .append(GREEN).append(requestUrl)
                .append(RESET).append("\n")
                .append(BOLD).append("HTTP RESPONSE : ")
                .append(RESET)
                .append(GREEN).append("200 OK")
                .append(RESET)
                .append("\n")
                .append(BOLD).append("CONTENT-TYPE  : ")
                .append(RESET)
                .append(YELLOW).append("application/json")
                .append(RESET).append("\n")
                .append(CYAN).repeat("-", 80)
                .append(RESET).append("\n")
                .append(json).append("\n")
                .append(CYAN).repeat("-", 80)
                .append(RESET).append("\n")
                .append(BOLD).append("TABLE VIEW:")
                .append(RESET).append("\n")
                .append(String.format("| %-4s | %-12s | %-24s | %-6s |\n", "ID", "USERNAME", "EMAIL", "ROLE"))
                .append("|")
                .repeat("-", 6).append("|")
                .repeat("-", 14).append("|")
                .repeat("-", 26).append("|")
                .repeat("-", 8).append("|\n");

        for (UserResponseDto item : response.contents()) {
            output.append(String.format("| %-4d | %-12s | %-24s | %-6s |\n", item.id(), item.username(), item.email(), item.role()));
        }

        output.append(CYAN).repeat("-", 80).append(RESET).append("\n")
                .append(String.format("Pagination: Page %d of %d | Items %d-%d of %d | [First: %b] [Last: %b] [HasNext: %b] [HasPrev: %b]\n",
                        response.currentPage() + 1,
                        response.totalPages(),
                        (response.currentPage() * response.pageSize()) + 1,
                        Math.min((response.currentPage() + 1) * response.pageSize(), (int) response.totalElements()),
                        response.totalElements(),
                        response.isFirstPage(),
                        response.isLastPage(),
                        response.hasNext(),
                        response.hasPrevious()
                ))
                .append(CYAN).repeat("=", 80).append(RESET);

        log.info("{}", output);
    }

    private String formatAsPrettyJson(PageResponse<UserResponseDto> response) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  ").append(CYAN).append("\"contents\"").append(RESET).append(": [\n");
        for (int i = 0; i < response.contents().size(); i++) {
            UserResponseDto item = response.contents().get(i);
            sb.append("    {\n");
            sb.append(String.format("      %s\"id\"%s: %s%d%s,\n", CYAN, RESET, YELLOW, item.id(), RESET));
            sb.append(String.format("      %s\"username\"%s: %s\"%s\"%s,\n", CYAN, RESET, GREEN, item.username(), RESET));
            sb.append(String.format("      %s\"email\"%s: %s\"%s\"%s,\n", CYAN, RESET, GREEN, item.email(), RESET));
            sb.append(String.format("      %s\"role\"%s: %s\"%s\"%s\n", CYAN, RESET, GREEN, item.role(), RESET));
            sb.append("    }").append(i < response.contents().size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ],\n");
        sb.append(String.format("  %s\"currentPage\"%s: %s%d%s,\n", CYAN, RESET, YELLOW, response.currentPage(), RESET));
        sb.append(String.format("  %s\"pageSize\"%s: %s%d%s,\n", CYAN, RESET, YELLOW, response.pageSize(), RESET));
        sb.append(String.format("  %s\"totalElements\"%s: %s%d%s,\n", CYAN, RESET, YELLOW, response.totalElements(), RESET));
        sb.append(String.format("  %s\"totalPages\"%s: %s%d%s,\n", CYAN, RESET, YELLOW, response.totalPages(), RESET));
        sb.append(String.format("  %s\"isLastPage\"%s: %s%b%s,\n", CYAN, RESET, BLUE, response.isLastPage(), RESET));
        sb.append(String.format("  %s\"isFirstPage\"%s: %s%b%s\n", CYAN, RESET, BLUE, response.isFirstPage(), RESET));
        sb.append("}");
        return sb.toString();
    }

    private record UserAccount(
            Long id,
            String username,
            String email,
            String role,
            String status
    ) {
        static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private Long id;
            private String username;
            private String email;
            private String role;
            private String status;

            private Builder() {
            }

            Builder id(final Long id) {
                this.id = id;
                return this;
            }

            Builder username(final String username) {
                this.username = username;
                return this;
            }

            Builder email(final String email) {
                this.email = email;
                return this;
            }

            Builder role(final String role) {
                this.role = role;
                return this;
            }

            Builder status() {
                this.status = "ACTIVE";
                return this;
            }

            UserAccount build() {
                return new UserAccount(id, username, email, role, status);
            }
        }
    }

    private record UserResponseDto(
            Long id,
            String username,
            String email,
            String role
    ) {

        static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private Long id;
            private String username;
            private String email;
            private String role;

            private Builder() {
            }

            Builder id(final Long id) {
                this.id = id;
                return this;
            }

            Builder username(final String username) {
                this.username = username;
                return this;
            }

            Builder email(final String email) {
                this.email = email;
                return this;
            }

            Builder role(final String role) {
                this.role = role;
                return this;
            }

            UserResponseDto build() {
                return new UserResponseDto(id, username, email, role);
            }
        }
    }
}
