package io.veg.cli.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Minimal dependency-free RFC 8259 JSON parser and serializer used by {@code veg-cli}
 * for the MCP stdio server and VEG Access Studio REST API.
 */
public final class JsonCodec {

    private JsonCodec() {
    }

    public static String toJson(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value);
        return sb.toString();
    }

    public static Object parse(String json) {
        Objects.requireNonNull(json, "json must not be null");
        Parser parser = new Parser(json);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.isAtEnd()) {
            throw new IllegalArgumentException("Unexpected trailing JSON content at position " + parser.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object parsed = parse(json);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Expected JSON array");
        }
        return (List<Object>) list;
    }

    public static Optional<String> asOptionalString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return Optional.empty();
        }
        if (v instanceof String s) {
            return Optional.of(s);
        }
        return Optional.of(String.valueOf(v));
    }

    public static String requireString(Map<String, Object> map, String key) {
        return asOptionalString(map, key)
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("Missing or blank required field: " + key));
    }

    public static Optional<Long> asOptionalLong(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return Optional.empty();
        }
        if (v instanceof Number n) {
            return Optional.of(n.longValue());
        }
        if (v instanceof String s && !s.isBlank()) {
            return Optional.of(Long.parseLong(s.trim()));
        }
        throw new IllegalArgumentException("Expected integer for field: " + key);
    }

    public static int asInt(Map<String, Object> map, String key, int defaultValue) {
        return asOptionalLong(map, key).map(Long::intValue).orElse(defaultValue);
    }

    public static boolean asBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object v = map.get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        throw new IllegalArgumentException("Expected boolean for field: " + key);
    }

    private static void writeValue(StringBuilder sb, Object value) {
        switch (value) {
            case null -> sb.append("null");
            case Boolean b -> sb.append(b ? "true" : "false");
            case Number n -> {
                if (n instanceof Double d && (!Double.isFinite(d))) {
                    sb.append("null");
                } else if (n instanceof Float f && (!Float.isFinite(f))) {
                    sb.append("null");
                } else {
                    sb.append(n);
                }
            }
            case String s -> writeString(sb, s);
            case Enum<?> e -> writeString(sb, e.name());
            case Map<?, ?> map -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    writeString(sb, String.valueOf(entry.getKey()));
                    sb.append(':');
                    writeValue(sb, entry.getValue());
                }
                sb.append('}');
            }
            case Iterable<?> it -> {
                sb.append('[');
                boolean first = true;
                for (Object elem : it) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    writeValue(sb, elem);
                }
                sb.append(']');
            }
            case Object[] arr -> {
                sb.append('[');
                for (int i = 0; i < arr.length; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    writeValue(sb, arr[i]);
                }
                sb.append(']');
            }
            default -> writeString(sb, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        boolean isAtEnd() {
            return pos >= src.length();
        }

        void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (isAtEnd()) {
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield parseNumber();
                    }
                    throw new IllegalArgumentException("Unexpected token '" + c + "' at position " + pos);
                }
            };
        }

        private Map<String, Object> parseObject() {
            pos++; // '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (!isAtEnd() && src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (isAtEnd() || src.charAt(pos) != '"') {
                    throw new IllegalArgumentException("Expected JSON object key string at position " + pos);
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object val = parseValue();
                map.put(key, val);
                skipWhitespace();
                if (isAtEnd()) {
                    throw new IllegalArgumentException("Unterminated JSON object");
                }
                char next = src.charAt(pos++);
                if (next == '}') {
                    return map;
                }
                if (next != ',') {
                    throw new IllegalArgumentException("Expected ',' or '}' in object at position " + (pos - 1));
                }
            }
        }

        private List<Object> parseArray() {
            pos++; // '['
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (!isAtEnd() && src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (isAtEnd()) {
                    throw new IllegalArgumentException("Unterminated JSON array");
                }
                char next = src.charAt(pos++);
                if (next == ']') {
                    return list;
                }
                if (next != ',') {
                    throw new IllegalArgumentException("Expected ',' or ']' in array at position " + (pos - 1));
                }
            }
        }

        private String parseString() {
            pos++; // '"'
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (isAtEnd()) {
                        throw new IllegalArgumentException("Unterminated escape sequence");
                    }
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"', '\\', '/' -> sb.append(esc);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > src.length()) {
                                throw new IllegalArgumentException("Incomplete unicode escape");
                            }
                            String hex = src.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("Invalid escape '\\" + esc + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private Object parseLiteral(String literal, Object value) {
            if (!src.startsWith(literal, pos)) {
                throw new IllegalArgumentException("Invalid literal at position " + pos);
            }
            pos += literal.length();
            return value;
        }

        private Number parseNumber() {
            int start = pos;
            if (src.charAt(pos) == '-') {
                pos++;
            }
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    if ((c == '+' || c == '-') && pos > start && src.charAt(pos - 1) != 'e' && src.charAt(pos - 1) != 'E') {
                        break;
                    }
                    pos++;
                } else {
                    break;
                }
            }
            String token = src.substring(start, pos);
            boolean isDecimal = token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0;
            if (!isDecimal) {
                try {
                    return Long.parseLong(token);
                } catch (NumberFormatException ignored) {
                    return new BigDecimal(token);
                }
            }
            return new BigDecimal(token);
        }

        private void expect(char expected) {
            if (isAtEnd() || src.charAt(pos) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at position " + pos);
            }
            pos++;
        }
    }
}
