package io.github.pranit.scroll.service;

import io.github.pranit.scroll.exception.InvalidScrollingException;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Encodes and decodes keyset scroll positions to and from compact, URL-safe opaque cursor tokens.
 */
public final class ScrollPositionCodec {

    private ScrollPositionCodec() {
        throw new UnsupportedOperationException("ScrollPositionCodec is a utility class and cannot be instantiated");
    }

    /**
     * Encodes a {@link ScrollPosition} and {@link ScrollDirection} into a Base64 URL-safe token.
     *
     * @param position  the keyset scroll position to encode
     * @param direction the direction to associate with this position
     * @return the Base64 URL-encoded token, or {@code null} if the position is null or initial
     */
    public static @Nullable String encode(final @Nullable ScrollPosition position, final @Nullable ScrollDirection direction) {
        if (position == null || position.isInitial()) return null;
        if (!(position instanceof KeysetScrollPosition keysetPosition)) return null;
        final ScrollDirection safeDirection = direction != null ? direction : ScrollDirection.FORWARD;
        final StringBuilder sb = new StringBuilder();
        sb.append("direction=").append(safeDirection.name());
        for (final Map.Entry<String, Object> entry : keysetPosition.getKeys().entrySet()) {
            sb.append(";").append(entry.getKey()).append("=").append(encodeValue(entry.getValue()));
        }
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeValue(final @Nullable Object value) {
        if (value == null) return "NULL:";
        final String typeTag;
        final String stringVal;
        switch (value) {
            case Long l -> {
                typeTag = "L:";
                stringVal = l.toString();
            }
            case Integer i -> {
                typeTag = "I:";
                stringVal = i.toString();
            }
            case UUID u -> {
                typeTag = "U:";
                stringVal = u.toString();
            }
            case Instant inst -> {
                typeTag = "T:";
                stringVal = inst.toString();
            }
            case Double d -> {
                typeTag = "D:";
                stringVal = d.toString();
            }
            case Float f -> {
                typeTag = "F:";
                stringVal = f.toString();
            }
            case BigDecimal bd -> {
                typeTag = "BD:";
                stringVal = bd.toString();
            }
            case BigInteger bi -> {
                typeTag = "BI:";
                stringVal = bi.toString();
            }
            case Boolean b -> {
                typeTag = "Z:";
                stringVal = b.toString();
            }
            case Short s -> {
                typeTag = "S:";
                stringVal = s.toString();
            }
            case Byte by -> {
                typeTag = "BY:";
                stringVal = by.toString();
            }
            case LocalDate ld -> {
                typeTag = "LD:";
                stringVal = ld.toString();
            }
            case LocalDateTime ldt -> {
                typeTag = "LDT:";
                stringVal = ldt.toString();
            }
            case OffsetDateTime odt -> {
                typeTag = "ODT:";
                stringVal = odt.toString();
            }
            case ZonedDateTime zdt -> {
                typeTag = "ZDT:";
                stringVal = zdt.toString();
            }
            default -> {
                typeTag = "STR:";
                stringVal = value.toString();
            }
        }
        return typeTag + URLEncoder.encode(stringVal, StandardCharsets.UTF_8);
    }

    /**
     * Decodes a Base64 URL-safe cursor token into a {@link DecodedScrollPosition}.
     * If the token is null or blank, returns an initial keyset position with {@link ScrollDirection#FORWARD}.
     *
     * @param encodedScrollPosition the token to decode
     * @return the decoded scroll position and direction
     * @throws InvalidScrollingException if the token cannot be decoded or is corrupted
     */
    public static DecodedScrollPosition decode(final @Nullable String encodedScrollPosition) {
        if (encodedScrollPosition == null || encodedScrollPosition.isBlank()) {
            return DecodedScrollPosition.builder()
                    .position(ScrollPosition.keyset())
                    .direction(ScrollDirection.FORWARD)
                    .build();
        }
        try {
            final String decoded = new String(Base64.getUrlDecoder().decode(encodedScrollPosition.trim()), StandardCharsets.UTF_8);
            final String[] pairs = decoded.split(";");
            final Map<String, Object> keys = new LinkedHashMap<>();
            ScrollDirection direction = ScrollDirection.FORWARD;
            for (final String pair : pairs) {
                final String[] keyValue = pair.split("=", 2);
                if (keyValue.length != 2) continue;
                final String key = keyValue[0];
                final String rawValue = keyValue[1];
                if ("direction".equalsIgnoreCase(key)) {
                    try {
                        direction = ScrollDirection.from(rawValue);
                    } catch (IllegalArgumentException ignored) {
                        direction = ScrollDirection.FORWARD;
                    }
                    continue;
                }
                keys.put(key, decodeValue(rawValue));
            }
            final ScrollPosition position = (direction == ScrollDirection.BACKWARD)
                    ? ScrollPosition.backward(keys) : ScrollPosition.forward(keys);
            return DecodedScrollPosition.builder()
                    .position(position)
                    .direction(direction)
                    .build();
        } catch (Exception e) {
            throw new InvalidScrollingException("Invalid scrollId: " + e.getMessage(), e);
        }
    }

    private static @Nullable Object decodeValue(final @Nullable String encoded) {
        if (encoded == null || encoded.isEmpty() || "NULL:".equals(encoded)) return null;
        if (encoded.startsWith("L:")) {
            return Long.valueOf(URLDecoder.decode(encoded.substring(2), StandardCharsets.UTF_8));
        }
        if (encoded.startsWith("I:")) {
            return Integer.valueOf(URLDecoder.decode(encoded.substring(2), StandardCharsets.UTF_8));
        }
        if (encoded.startsWith("U:")) {
            return UUID.fromString(URLDecoder.decode(encoded.substring(2), StandardCharsets.UTF_8));
        }
        if (encoded.startsWith("T:")) {
            return Instant.parse(URLDecoder.decode(encoded.substring(2), StandardCharsets.UTF_8));
        }
        if (encoded.startsWith("D:")) {
            return Double.valueOf(URLDecoder.decode(encoded.substring(2), StandardCharsets.UTF_8));
        }
        if (encoded.startsWith("F:")) {
            return Float.valueOf(URLDecoder.decode(encoded.substring(2), StandardCharsets.UTF_8));
        }
        if (encoded.startsWith("BD:")) {
            return new BigDecimal(URLDecoder.decode(encoded.substring(3), StandardCharsets.UTF_8));
        }
        if (encoded.startsWith("BI:")) {
            return new BigInteger(URLDecoder.decode(encoded.substring(3), StandardCharsets.UTF_8));
        }
        if (encoded.startsWith("Z:")) {
            return Boolean.valueOf(URLDecoder.decode(encoded.substring(2), StandardCharsets.UTF_8));
        }
        if (encoded.startsWith("S:")) {
            return Short.valueOf(URLDecoder.decode(encoded.substring(2), StandardCharsets.UTF_8));
        }
        if (encoded.startsWith("BY:")) {
            return Byte.valueOf(URLDecoder.decode(encoded.substring(3), StandardCharsets.UTF_8));
        }
        if (encoded.startsWith("LD:")) {
            return LocalDate.parse(URLDecoder.decode(encoded.substring(3), StandardCharsets.UTF_8));
        }
        if (encoded.startsWith("LDT:")) {
            return LocalDateTime.parse(URLDecoder.decode(encoded.substring(4), StandardCharsets.UTF_8));
        }
        if (encoded.startsWith("ODT:")) {
            return OffsetDateTime.parse(URLDecoder.decode(encoded.substring(4), StandardCharsets.UTF_8));
        }
        if (encoded.startsWith("ZDT:")) {
            return ZonedDateTime.parse(URLDecoder.decode(encoded.substring(4), StandardCharsets.UTF_8));
        }
        if (encoded.startsWith("STR:")) {
            return URLDecoder.decode(encoded.substring(4), StandardCharsets.UTF_8);
        }
        final String decodedStr = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        try {
            return UUID.fromString(decodedStr);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            return Long.valueOf(decodedStr);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Instant.parse(decodedStr);
        } catch (Exception ignored) {
        }
        return decodedStr;
    }

    /**
     * Direction of the scrolling cursor navigation.
     */
    public enum ScrollDirection {
        FORWARD,
        BACKWARD;

        public static ScrollDirection from(final String direction) {
            if (direction == null || direction.isBlank()) return FORWARD;
            return switch (direction.trim().toLowerCase(Locale.ROOT)) {
                case "forward" -> FORWARD;
                case "backward" -> BACKWARD;
                default -> throw new IllegalArgumentException("Scroll direction must be 'forward' or 'backward'");
            };
        }

        public ScrollDirection reverse() {
            return this == FORWARD ? BACKWARD : FORWARD;
        }
    }

    /**
     * Represents a decoded scroll position and associated scroll direction.
     */
    public record DecodedScrollPosition(
            ScrollPosition position,
            ScrollDirection direction
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private ScrollPosition position;
            private ScrollDirection direction;

            private Builder() {
            }

            public Builder position(final ScrollPosition position) {
                this.position = position;
                return this;
            }

            public Builder direction(final ScrollDirection direction) {
                this.direction = direction;
                return this;
            }

            public DecodedScrollPosition build() {
                return new DecodedScrollPosition(position, direction);
            }
        }
    }
}
