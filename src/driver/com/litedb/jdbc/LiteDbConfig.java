package com.litedb.jdbc;

import java.io.File;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

final class LiteDbConfig {
    final String url;
    final String databasePath;
    final String password;
    final boolean readOnly;
    final String bridgePath;
    final String connectionMode;
    final int columnSampleSize;
    final boolean create;
    final int defaultMaxRows;
    final long connectTimeoutMillis;

    private LiteDbConfig(
            String url,
            String databasePath,
            String password,
            boolean readOnly,
            String bridgePath,
            String connectionMode,
            int columnSampleSize,
            boolean create,
            int defaultMaxRows,
            long connectTimeoutMillis) {
        this.url = url;
        this.databasePath = databasePath;
        this.password = password;
        this.readOnly = readOnly;
        this.bridgePath = bridgePath;
        this.connectionMode = connectionMode;
        this.columnSampleSize = columnSampleSize;
        this.create = create;
        this.defaultMaxRows = defaultMaxRows;
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    static LiteDbConfig parse(String url, Properties properties) {
        Properties merged = new Properties();
        if (properties != null) {
            merged.putAll(properties);
        }

        String body = url.substring(LiteDbDriver.URL_PREFIX.length()).trim();
        SplitUrl split = splitUrlQuery(body);
        Map<String, String> inline = parseInlineProperties(split.pathPart);
        inline.putAll(parseQueryProperties(split.queryPart));
        for (Map.Entry<String, String> entry : inline.entrySet()) {
            merged.setProperty(entry.getKey(), entry.getValue());
        }

        String databasePath = property(
                merged,
                "databasePath", "database", "file", "filename", "path", "dataSource", "data source", "_path");
        if (databasePath == null) {
            throw new IllegalArgumentException("Missing LiteDB database path. Use jdbc:litedb:<file> or jdbc:litedb:file=<file>.");
        }

        return new LiteDbConfig(
                url,
                normalizeDatabasePath(databasePath),
                blankToNull(property(merged, "password", "pwd", "pass")),
                parseBoolean(property(merged, "readOnly", "readonly", "read only"), false),
                blankToNull(property(merged, "bridgePath", "bridgepath", "bridge")),
                normalizeConnectionMode(property(merged, "connectionMode", "connection", "mode")),
                parseInteger(property(merged, "columnSampleSize", "columnSample", "sampleSize"), 200),
                parseBoolean(property(merged, "create"), false),
                parseInteger(property(merged, "maxRows"), 10000),
                parseLong(property(merged, "connectTimeout"), 0L) * 1000L);
    }

    private static Map<String, String> parseInlineProperties(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        if (body.isEmpty()) {
            return values;
        }

        if (body.indexOf('=') < 0 || looksLikeWindowsPath(body)) {
            values.put("_path", normalizePathValue(decode(body)));
            return values;
        }

        String[] parts = body.split(";");
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }

            int separator = part.indexOf('=');
            if (separator < 0) {
                values.put("_path", normalizePathValue(decode(part.trim())));
                continue;
            }

            String key = part.substring(0, separator).trim();
            String value = normalizePathValue(decode(part.substring(separator + 1).trim()));
            values.put(key, value);
        }

        return values;
    }

    private static Map<String, String> parseQueryProperties(String query) {
        Map<String, String> values = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return values;
        }

        String[] parts = query.split("[&;]");
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }

            int separator = part.indexOf('=');
            if (separator < 0) {
                values.put(decode(part.trim()), "true");
                continue;
            }

            values.put(
                    decode(part.substring(0, separator).trim()),
                    normalizePathValue(decode(part.substring(separator + 1).trim())));
        }

        return values;
    }

    private static String normalizeDatabasePath(String value) {
        String path = normalizePathValue(value);
        File file = new File(path);
        if (pathExistsOrParentExists(file)) {
            return file.getPath();
        }

        String repaired = repairUtf8Mojibake(path);
        if (!repaired.equals(path)) {
            File repairedFile = new File(repaired);
            if (pathExistsOrParentExists(repairedFile)) {
                return repairedFile.getPath();
            }
        }

        return file.getPath();
    }

    private static String normalizePathValue(String value) {
        if (value == null) {
            return null;
        }

        String path = stripQuotes(value.trim());
        if (path.startsWith("file:")) {
            try {
                return Paths.get(URI.create(path)).toFile().getPath();
            } catch (RuntimeException ignored) {
                return stripFilePrefix(path);
            }
        }

        return path;
    }

    private static boolean pathExistsOrParentExists(File file) {
        if (file.exists()) {
            return true;
        }

        File parent = file.getAbsoluteFile().getParentFile();
        return parent != null && parent.exists();
    }

    private static String repairUtf8Mojibake(String value) {
        String repaired = repairUtf8Mojibake(value, Charset.defaultCharset());
        if (!repaired.equals(value)) {
            return repaired;
        }

        return repairUtf8Mojibake(value, Charset.forName("GB18030"));
    }

    private static String repairUtf8Mojibake(String value, Charset legacyCharset) {
        if (StandardCharsets.UTF_8.equals(legacyCharset)) {
            return value;
        }

        try {
            String repaired = new String(value.getBytes(legacyCharset), StandardCharsets.UTF_8);
            return repaired.indexOf('\uFFFD') >= 0 ? value : repaired;
        } catch (RuntimeException ex) {
            return value;
        }
    }

    private static boolean looksLikeWindowsPath(String value) {
        return value.length() >= 3
                && Character.isLetter(value.charAt(0))
                && value.charAt(1) == ':'
                && (value.charAt(2) == '\\' || value.charAt(2) == '/');
    }

    private static String stripFilePrefix(String value) {
        if (value.startsWith("file:///")) {
            return value.substring("file:///".length());
        }
        if (value.startsWith("file:/")) {
            return value.substring("file:/".length());
        }
        if (value.startsWith("file:")) {
            return value.substring("file:".length());
        }

        return value;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }

        return value;
    }

    private static SplitUrl splitUrlQuery(String body) {
        int query = body.indexOf('?');
        if (query < 0) {
            return new SplitUrl(body, null);
        }

        return new SplitUrl(body.substring(0, query), body.substring(query + 1));
    }

    private static String decode(String value) {
        // Decode only %xx escapes. '+' is legal in Windows paths and passwords, so it
        // must not become a space: form-urlencoded semantics do not apply to JDBC URLs
        // like jdbc:litedb:C:\data\my+db.litedb or password=abc+def.
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return Boolean.parseBoolean(value.trim());
    }

    private static String normalizeConnectionMode(String value) {
        String mode = firstNonBlank(value, "shared");
        return "direct".equalsIgnoreCase(mode) ? "direct" : "shared";
    }

    private static int parseInteger(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static long parseLong(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        return null;
    }

    private static String property(Properties properties, String... names) {
        for (String name : names) {
            String value = properties.getProperty(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        for (String name : names) {
            for (String key : properties.stringPropertyNames()) {
                if (key.equalsIgnoreCase(name)) {
                    String value = properties.getProperty(key);
                    if (value != null && !value.isBlank()) {
                        return value.trim();
                    }
                }
            }
        }

        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static final class SplitUrl {
        final String pathPart;
        final String queryPart;

        SplitUrl(String pathPart, String queryPart) {
            this.pathPart = pathPart;
            this.queryPart = queryPart;
        }
    }
}
