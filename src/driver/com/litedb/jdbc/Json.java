package com.litedb.jdbc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Json {
    private Json() {
    }

    static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.isEnd()) {
            throw new IllegalArgumentException("Unexpected JSON trailing content.");
        }

        return value;
    }

    static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        writeValue(builder, value);
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String) {
            writeString(builder, (String) value);
        } else if (value instanceof Number || value instanceof Boolean) {
            builder.append(value);
        } else if (value instanceof Map) {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                writeString(builder, String.valueOf(entry.getKey()));
                builder.append(':');
                writeValue(builder, entry.getValue());
            }
            builder.append('}');
        } else if (value instanceof Iterable) {
            builder.append('[');
            boolean first = true;
            for (Object item : (Iterable<Object>) value) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                writeValue(builder, item);
            }
            builder.append(']');
        } else if (value.getClass().isArray()) {
            builder.append('[');
            Object[] values = (Object[]) value;
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    builder.append(',');
                }
                writeValue(builder, values[i]);
            }
            builder.append(']');
        } else {
            writeString(builder, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder builder, String value) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    builder.append("\\\"");
                    break;
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\f':
                    builder.append("\\f");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                    break;
            }
        }
        builder.append('"');
    }

    private static final class Parser {
        private static final int MAX_DEPTH = 512;
        private final String text;
        private int position;
        private int depth;
        private boolean expectLowSurrogate;

        Parser(String text) {
            this.text = text;
        }

        boolean isEnd() {
            return position >= text.length();
        }

        void skipWhitespace() {
            while (!isEnd() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (isEnd()) {
                throw new IllegalArgumentException("Unexpected end of JSON.");
            }

            char c = text.charAt(position);
            if (c == '"') {
                return parseString();
            }
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == 't') {
                expect("true");
                return Boolean.TRUE;
            }
            if (c == 'f') {
                expect("false");
                return Boolean.FALSE;
            }
            if (c == 'n') {
                expect("null");
                return null;
            }
            if (c == '-' || Character.isDigit(c)) {
                return parseNumber();
            }

            throw new IllegalArgumentException("Unexpected JSON character '" + c + "'.");
        }

        private Map<String, Object> parseObject() {
            expect('{');
            enterNested();
            Map<String, Object> value = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                position++;
                depth--;
                return value;
            }

            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                value.put(key, parseValue());
                skipWhitespace();
                if (peek('}')) {
                    position++;
                    depth--;
                    return value;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            enterNested();
            List<Object> value = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                position++;
                depth--;
                return value;
            }

            while (true) {
                value.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    position++;
                    depth--;
                    return value;
                }
                expect(',');
            }
        }

        private void enterNested() {
            depth++;
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("JSON nesting exceeds " + MAX_DEPTH + " levels.");
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (!isEnd()) {
                char c = text.charAt(position++);
                if (c == '"') {
                    return builder.toString();
                }
                if (c != '\\') {
                    builder.append(c);
                    continue;
                }
                if (isEnd()) {
                    throw new IllegalArgumentException("Invalid JSON escape.");
                }

                char escape = text.charAt(position++);
                switch (escape) {
                    case '"':
                    case '\\':
                    case '/':
                        builder.append(escape);
                        break;
                    case 'b':
                        builder.append('\b');
                        break;
                    case 'f':
                        builder.append('\f');
                        break;
                    case 'n':
                        builder.append('\n');
                        break;
                    case 'r':
                        builder.append('\r');
                        break;
                    case 't':
                        builder.append('\t');
                        break;
                    case 'u':
                        builder.append(parseUnicodeEscape());
                        break;
                    default:
                        throw new IllegalArgumentException("Invalid JSON escape '\\" + escape + "'.");
                }
            }

            throw new IllegalArgumentException("Unterminated JSON string.");
        }

        private char parseUnicodeEscape() {
            if (position + 4 > text.length()) {
                throw new IllegalArgumentException("Invalid JSON unicode escape.");
            }

            String hex = text.substring(position, position + 4);
            position += 4;
            for (int i = 0; i < hex.length(); i++) {
                if (Character.digit(hex.charAt(i), 16) < 0) {
                    throw new IllegalArgumentException("Invalid JSON unicode escape '\\u" + hex + "'.");
                }
            }
            char decoded = (char) Integer.parseInt(hex, 16);

            if (Character.isHighSurrogate(decoded)) {
                // A high surrogate must be immediately followed by its low-pair escape
                // (backslash-u form); both halves are appended in sequence and encode
                // to valid UTF-8.
                if (position + 6 > text.length() || text.charAt(position) != '\\'
                        || text.charAt(position + 1) != 'u') {
                    throw new IllegalArgumentException("Unpaired high surrogate in JSON string.");
                }
                String lowHex = text.substring(position + 2, position + 6);
                char low = expectHexQuad(lowHex);
                if (!Character.isLowSurrogate(low)) {
                    throw new IllegalArgumentException("Unpaired high surrogate in JSON string.");
                }
                expectLowSurrogate = true;
            } else if (Character.isLowSurrogate(decoded)) {
                if (!expectLowSurrogate) {
                    throw new IllegalArgumentException("Unpaired low surrogate in JSON string.");
                }
                expectLowSurrogate = false;
            } else {
                expectLowSurrogate = false;
            }
            return decoded;
        }

        private char expectHexQuad(String hex) {
            for (int i = 0; i < hex.length(); i++) {
                if (Character.digit(hex.charAt(i), 16) < 0) {
                    throw new IllegalArgumentException("Invalid JSON unicode escape '\\u" + hex + "'.");
                }
            }
            return (char) Integer.parseInt(hex, 16);
        }

        private Number parseNumber() {
            int start = position;
            if (peek('-')) {
                position++;
            }
            while (!isEnd() && Character.isDigit(text.charAt(position))) {
                position++;
            }
            boolean floating = false;
            if (!isEnd() && text.charAt(position) == '.') {
                floating = true;
                position++;
                while (!isEnd() && Character.isDigit(text.charAt(position))) {
                    position++;
                }
            }
            if (!isEnd() && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
                floating = true;
                position++;
                if (!isEnd() && (text.charAt(position) == '+' || text.charAt(position) == '-')) {
                    position++;
                }
                while (!isEnd() && Character.isDigit(text.charAt(position))) {
                    position++;
                }
            }

            String number = text.substring(start, position);
            if (floating) {
                // Full Decimal128/Double precision travels best as BigDecimal; callers
                // convert to the declared column type (getObject converts by declared type).
                return new BigDecimal(number);
            }
            try {
                return Long.valueOf(number);
            } catch (NumberFormatException ex) {
                // Integers beyond the long range (for example LiteDB Decimal128 scalars
                // written by other clients) must survive the round trip, not kill the connection.
                return new BigDecimal(number);
            }
        }

        private boolean peek(char expected) {
            return !isEnd() && text.charAt(position) == expected;
        }

        private void expect(char expected) {
            if (isEnd() || text.charAt(position) != expected) {
                throw new IllegalArgumentException("Expected JSON character '" + expected + "'.");
            }
            position++;
        }

        private void expect(String expected) {
            if (!text.startsWith(expected, position)) {
                throw new IllegalArgumentException("Expected JSON token '" + expected + "'.");
            }
            position += expected.length();
        }
    }
}
