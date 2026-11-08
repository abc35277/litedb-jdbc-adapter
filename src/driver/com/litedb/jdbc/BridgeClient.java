package com.litedb.jdbc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

final class BridgeClient implements Closeable {
    private static final long CLOSE_TIMEOUT_MILLIS = 3000;
    private static final long DESTROY_TIMEOUT_MILLIS = 1000;

    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<Object>> pending = new ConcurrentHashMap<>();
    private final Map<Long, StreamCollector> streams = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private final Thread shutdownHook;
    private final Thread readerThread;
    private volatile CompletableFuture<Object> active;
    private volatile boolean closed;

    private BridgeClient(Process process) {
        this.process = process;
        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.shutdownHook = new Thread(this::destroyProcess, "litedb-jdbc-bridge-shutdown");
        this.readerThread = new Thread(this::readLoop, "litedb-jdbc-bridge-reader");
        this.readerThread.setDaemon(true);
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM is already shutting down; the process will be torn down by the caller.
        }
        this.readerThread.start();
    }

    static BridgeClient open(LiteDbConfig config) throws SQLException {
        ProcessBuilder builder = new ProcessBuilder(resolveCommand(config));
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);

        BridgeClient client = null;
        try {
            client = new BridgeClient(builder.start());
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("databasePath", config.databasePath);
            parameters.put("password", config.password);
            parameters.put("readOnly", config.readOnly);
            parameters.put("connectionMode", config.connectionMode);
            parameters.put("create", config.create);
            Object openResult = client.request("open", parameters, config.connectTimeoutMillis);
            checkBridgeCompatibility(openResult);
            return client;
        } catch (IOException ex) {
            closeQuietly(client);
            throw new SQLException("Unable to start LiteDB bridge process: " + ex.getMessage(), "08001", ex);
        } catch (SQLException | RuntimeException ex) {
            closeQuietly(client);
            throw ex;
        }
    }

    Object request(String method, Map<String, Object> parameters) throws SQLException {
        return request(method, parameters, 0L);
    }

    /**
     * Sends a request and waits for the matching response. A positive timeout
     * aborts the call with SQLState HYT01 and destroys the hung bridge process;
     * without a timeout the call waits as long as the underlying pipe does.
     * Cancel support ({@link #cancelActiveRequest()}) unblocks the caller without
     * desynchronizing the protocol: the late response is discarded by id.
     */
    Object request(String method, Map<String, Object> parameters, long timeoutMillis) throws SQLException {
        if (closed) {
            throw new SQLException("LiteDB bridge is closed.", "08003");
        }

        long id = ids.incrementAndGet();
        CompletableFuture<Object> future = new CompletableFuture<>();
        pending.put(id, future);
        active = future;
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("id", id);
            request.put("method", method);
            request.put("params", parameters == null ? Map.of() : parameters);
            try {
                synchronized (writeLock) {
                    writer.write(Json.write(request));
                    writer.newLine();
                    writer.flush();
                }
            } catch (IOException ex) {
                throw new SQLException("LiteDB bridge I/O failed: " + ex.getMessage(), "08006", ex);
            }

            try {
                if (timeoutMillis > 0) {
                    return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
                }
                return future.get();
            } catch (TimeoutException ex) {
                throw new SQLException(
                        "LiteDB bridge request '" + method + "' timed out after " + timeoutMillis + " ms.",
                        "HYT01", ex);
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof SQLException) {
                    throw (SQLException) cause;
                }
                throw new SQLException("LiteDB bridge request failed: " + cause.getMessage(), "HY000", cause);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while waiting for the LiteDB bridge.", "HY000", ex);
            }
        } catch (SQLException ex) {
            pending.remove(id);
            if ("HYT01".equals(ex.getSQLState())) {
                // The bridge is hung; it cannot serve further requests. Destroy it and
                // mark this client unusable (the connection may transparently reopen
                // while no transaction is active).
                destroyProcess();
                closed = true;
            }
            throw ex;
        } finally {
            pending.remove(id);
            if (active == future) {
                active = null;
            }
        }
    }

    /**
     * Cancels the most recent in-flight request (JDBC Statement.cancel). The
     * bridge keeps processing the statement, but the caller is unblocked with a
     * "cancelled" SQLException and the late response is discarded by id, so the
     * protocol stays in sync and the connection remains usable.
     */
    void cancelActiveRequest() {
        CompletableFuture<Object> future = active;
        if (future != null && !future.isDone()) {
            future.completeExceptionally(new SQLException("Statement was cancelled.", "57014"));
        }
    }

    /**
     * Streaming variant of {@link #request}: returns after the first response chunk
     * and exposes a collector for pulling later chunks. The final chunk carries
     * done=true; a null from {@link StreamCollector#poll} means exhausted.
     */
    StreamedResult requestStream(String method, Map<String, Object> parameters, long timeoutMillis) throws SQLException {
        if (closed) {
            throw new SQLException("LiteDB bridge is closed.", "08003");
        }

        long id = ids.incrementAndGet();
        CompletableFuture<Object> future = new CompletableFuture<>();
        StreamCollector collector = new StreamCollector(id);
        streams.put(id, collector);
        pending.put(id, future);
        active = future;
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("id", id);
            request.put("method", method);
            request.put("params", parameters == null ? Map.of() : parameters);
            try {
                synchronized (writeLock) {
                    writer.write(Json.write(request));
                    writer.newLine();
                    writer.flush();
            }
            } catch (IOException ex) {
                throw new SQLException("LiteDB bridge I/O failed: " + ex.getMessage(), "08006", ex);
            }

            try {
                if (timeoutMillis > 0) {
                    future.get(timeoutMillis, TimeUnit.MILLISECONDS);
                } else {
                    future.get();
                }
                return new StreamedResult(collector);
            } catch (TimeoutException ex) {
                throw new SQLException(
                        "LiteDB bridge request '" + method + "' timed out after " + timeoutMillis + " ms.",
                        "HYT01", ex);
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof SQLException) {
                    throw (SQLException) cause;
                }
                throw new SQLException("LiteDB bridge request failed: " + cause.getMessage(), "HY000", cause);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while waiting for the LiteDB bridge.", "HY000", ex);
            }
        } catch (SQLException ex) {
            pending.remove(id);
            streams.remove(id);
            if ("HYT01".equals(ex.getSQLState())) {
                destroyProcess();
                closed = true;
            }
            throw ex;
        } finally {
            pending.remove(id);
            if (active == future) {
                active = null;
            }
        }
    }

    /** Pulls the next chunk for a streaming response; null means the stream is done. */
    Object pollChunk(StreamCollector collector, long timeoutMillis) throws SQLException {
        if (collector.isDone() && collector.isEmpty()) {
            streams.remove(collector.id);
            return null;
        }
        if (collector.isDone()) {
            return collector.takeBuffered();
        }
        try {
            Object chunk = timeoutMillis > 0
                    ? collector.chunks.poll(timeoutMillis, TimeUnit.MILLISECONDS)
                    : collector.chunks.take();
            if (chunk == null) {
                closed = true;
                destroyProcess();
                throw new SQLException(
                        "LiteDB bridge request timed out while streaming results after " + timeoutMillis + " ms.",
                        "HYT01");
            }
            return chunk;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while reading the LiteDB bridge stream.", "HY000", ex);
        }
    }

    void markUnusable() {
        closed = true;
        destroyProcess();
    }

    /** Collects the chunks of one streaming response, in arrival order. */
    static final class StreamCollector {
        final long id;
        private final java.util.concurrent.BlockingQueue<Object> chunks = new java.util.concurrent.LinkedBlockingQueue<>();
        private volatile boolean done;
        private volatile boolean failed;

        StreamCollector(long id) {
            this.id = id;
        }

        void offer(Object chunk) {
            chunks.offer(chunk);
        }

        void markDone() {
            done = true;
        }

        void markFailed() {
            failed = true;
            done = true;
        }

        boolean isDone() {
            return done;
        }

        boolean isFailed() {
            return failed;
        }

        boolean isEmpty() {
            return chunks.isEmpty();
        }

        Object takeBuffered() {
            return chunks.poll();
        }
    }

    /** Head chunk plus collector handle for one streaming request. */
    static final class StreamedResult {
        final StreamCollector collector;

        StreamedResult(StreamCollector collector) {
            this.collector = collector;
        }
    }

    private void readLoop() {
        try {
            while (!closed) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                if (!handleLine(line)) {
                    break;
                }
            }
        } catch (IOException ignored) {
            // The process is gone or close() closed the pipe while we waited.
        } finally {
            closed = true;
            failAllPending(new SQLException("LiteDB bridge exited without a response.", "08006"));
        }
    }

    /** Returns false when the response stream is unusable and the loop must stop. */
    private boolean handleLine(String line) {
        Object parsed;
        try {
            parsed = Json.parse(line);
        } catch (RuntimeException ex) {
            failAllPending(new SQLException(
                    "LiteDB bridge returned an invalid response: " + ex.getMessage(), "HY000", ex));
            return false;
        }
        if (!(parsed instanceof Map)) {
            failAllPending(new SQLException("Invalid LiteDB bridge response.", "HY000"));
            return false;
        }

        Map<?, ?> response = (Map<?, ?>) parsed;
        Object responseId = response.get("id");
        if (!(responseId instanceof Number)) {
            // Error envelopes that lost their id cannot be matched; surface them for
            // diagnostics instead of silently swallowing them.
            System.err.println("LiteDB bridge: unmatched response: " + line);
            return true;
        }

        long responseIdLong = ((Number) responseId).longValue();
        CompletableFuture<Object> future = pending.remove(responseIdLong);
        StreamCollector collector = streams.get(responseIdLong);

        if (Boolean.TRUE.equals(response.get("ok"))) {
            Object result = response.get("result");
            if (collector != null && result instanceof Map && ((Map<?, ?>) result).containsKey("done")) {
                // Chunked execute stream: every chunk is offered in order; the first
                // chunk additionally completes the waiting future.
                collector.offer(result);
                if (Boolean.TRUE.equals(((Map<?, ?>) result).get("done"))) {
                    collector.markDone();
                }
                if (future != null) {
                    future.complete(result);
                }
                return true;
            }
            if (future != null) {
                future.complete(result);
            } else if (collector != null) {
                collector.markFailed();
            }
            return true;
        }

        String message = "LiteDB bridge request failed.";
        Object error = response.get("error");
        if (error instanceof Map && ((Map<?, ?>) error).get("message") != null) {
            message = String.valueOf(((Map<?, ?>) error).get("message"));
        }
        if (collector != null) {
            collector.markFailed();
        }
        if (future != null) {
            future.completeExceptionally(new SQLException(message, "HY000"));
        }
        return true;
    }

    private void failAllPending(SQLException failure) {
        for (CompletableFuture<Object> future : pending.values()) {
            future.completeExceptionally(failure);
        }
        pending.clear();
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }

        closed = true;
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("id", ids.incrementAndGet());
            request.put("method", "close");
            request.put("params", Map.of());
            synchronized (writeLock) {
                writer.write(Json.write(request));
                writer.newLine();
                writer.flush();
            }
        } catch (IOException ignored) {
        } finally {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
        }

        waitThenDestroy();

        try {
            reader.close();
        } catch (IOException ignored) {
        }

        removeShutdownHook();
    }

    private void waitThenDestroy() {
        try {
            if (process.waitFor(CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                return;
            }

            process.destroy();
            if (!process.waitFor(DESTROY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            destroyProcess();
        }
    }

    private void destroyProcess() {
        if (process.isAlive()) {
            process.destroy();
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private void removeShutdownHook() {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // Shutdown is in progress; the hook is either running or about to run.
        }
    }

    private static void closeQuietly(BridgeClient client) {
        if (client == null) {
            return;
        }

        try {
            client.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * Warns on stderr when the bridge's major.minor version differs from the
     * driver's, so version skew surfaces at connect time instead of as an
     * "Unsupported bridge method" error on first use.
     */
    private static void checkBridgeCompatibility(Object openResult) {
        if (!(openResult instanceof Map)) {
            return;
        }
        Object version = ((Map<?, ?>) openResult).get("bridgeVersion");
        if (!(version instanceof String) || ((String) version).isBlank()) {
            return;
        }
        String[] bridgeParts = ((String) version).split("[.]");
        String[] driverParts = LiteDbDriver.VERSION.split("\\.");
        try {
            int bridgeMajor = Integer.parseInt(bridgeParts[0].trim());
            int bridgeMinor = bridgeParts.length > 1 ? Integer.parseInt(bridgeParts[1].trim()) : 0;
            int driverMajor = Integer.parseInt(driverParts[0].trim());
            int driverMinor = driverParts.length > 1 ? Integer.parseInt(driverParts[1].trim()) : 0;
            if (bridgeMajor != driverMajor || bridgeMinor != driverMinor) {
                System.err.println(
                        "LiteDB JDBC: bridge version " + version + " differs from driver version "
                                + LiteDbDriver.VERSION
                                + ". If you see 'Unsupported bridge method' errors, update the bridge.");
            }
        } catch (NumberFormatException ignored) {
            // Unparsable bridge version: no reliable comparison possible.
        }
    }

    private static List<String> resolveCommand(LiteDbConfig config) throws SQLException {
        String configured = firstNonBlank(
                config.bridgePath,
                System.getProperty("litedb.jdbc.bridge"),
                System.getenv("LITEDB_JDBC_BRIDGE"));
        List<File> searched = new ArrayList<>();
        File bridge = findBridge(configured, searched);

        if (bridge == null) {
            throw new SQLException(
                    "Cannot locate LiteDB bridge. Set bridgePath, -Dlitedb.jdbc.bridge, or LITEDB_JDBC_BRIDGE. Searched: "
                            + describeFiles(searched),
                    "08001");
        }

        List<String> command = new ArrayList<>();
        if (bridge.getName().toLowerCase(Locale.ROOT).endsWith(".dll")) {
            command.add("dotnet");
            command.add(bridge.getAbsolutePath());
        } else {
            command.add(bridge.getAbsolutePath());
        }

        command.add("--stdio");
        return command;
    }

    private static File findBridge(String configured, List<File> searched) throws SQLException {
        File driverDirectory = findDriverDirectory();
        List<File> candidates = new ArrayList<>();

        if (configured != null) {
            // An explicitly configured bridge path must work; silently falling back to
            // the bundled bridge would hide misconfiguration and version skew.
            File configuredFile = new File(configured);
            candidates.add(configuredFile);
            if (!configuredFile.isAbsolute() && driverDirectory != null) {
                candidates.add(new File(driverDirectory, configured));
            }
            for (File candidate : candidates) {
                searched.add(candidate);
                File resolved = resolveBridgeExecutable(candidate);
                if (resolved != null) {
                    return resolved;
                }
            }
            throw new SQLException(
                    "The configured LiteDB bridge path does not contain LiteDbJdbc.Bridge.exe or "
                            + "LiteDbJdbc.Bridge.dll. Searched: " + describeFiles(searched)
                            + ". Remove the bridgePath property to use the bundled bridge.",
                    "08001");
        }

        addBundledBridgeCandidates(candidates, driverDirectory);

        for (File candidate : candidates) {
            searched.add(candidate);
            File resolved = resolveBridgeExecutable(candidate);
            if (resolved != null) {
                return resolved;
            }
        }

        return null;
    }

    private static String describeFiles(List<File> files) {
        if (files.isEmpty()) {
            return "(no candidate paths)";
        }

        List<String> paths = new ArrayList<>();
        for (File file : files) {
            paths.add(file.getAbsolutePath());
        }
        return String.join("; ", paths);
    }

    private static File resolveBridgeExecutable(File bridge) {
        if (bridge == null) {
            return null;
        }

        if (bridge.isDirectory()) {
            File exe = new File(bridge, "LiteDbJdbc.Bridge.exe");
            if (exe.isFile()) {
                return exe;
            }

            File dll = new File(bridge, "LiteDbJdbc.Bridge.dll");
            if (dll.isFile()) {
                return dll;
            }
        }

        return bridge.isFile() ? bridge : null;
    }

    private static void addBundledBridgeCandidates(List<File> candidates, File base) {
        if (base == null) {
            return;
        }

        File[] versioned = base.listFiles(file -> file.isDirectory()
                && file.getName().toLowerCase(Locale.ROOT).startsWith("bridge-"));
        if (versioned != null) {
            Arrays.sort(versioned, (left, right) -> compareBridgeVersions(right.getName(), left.getName()));
            for (File candidate : versioned) {
                candidates.add(candidate);
            }
        }

        File[] bundled = new File[] {
            new File(base, "bridge/LiteDbJdbc.Bridge.exe"),
            new File(base, "bridge/LiteDbJdbc.Bridge.dll"),
            new File(base, "bridge"),
            new File(base, "LiteDbJdbc.Bridge.exe"),
            new File(base, "LiteDbJdbc.Bridge.dll")
        };

        for (File candidate : bundled) {
            candidates.add(candidate);
        }
    }

    /** Compares "bridge-a.b.c" style directory names by parsed version, ascending. */
    static int compareBridgeVersions(String left, String right) {
        long[] leftParts = parseBridgeVersion(left);
        long[] rightParts = parseBridgeVersion(right);
        for (int i = 0; i < 3; i++) {
            int compared = Long.compare(leftParts[i], rightParts[i]);
            if (compared != 0) {
                return compared;
            }
        }
        return left.compareTo(right);
    }

    private static long[] parseBridgeVersion(String name) {
        long[] parts = new long[] { 0, 0, 0 };
        String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("bridge-")) {
            normalized = normalized.substring("bridge-".length());
        }
        String[] segments = normalized.split("[.\\-]");
        for (int i = 0; i < Math.min(3, segments.length); i++) {
            try {
                parts[i] = Long.parseLong(segments[i]);
            } catch (NumberFormatException ex) {
                break; // non-numeric segment: treat remaining parts as 0 (oldest)
            }
        }
        return parts;
    }

    private static File findDriverDirectory() {
        URL location = LiteDbDriver.class.getProtectionDomain().getCodeSource().getLocation();
        if (location == null) {
            return null;
        }

        try {
            File file = new File(location.toURI());
            return file.isFile() ? file.getParentFile() : file;
        } catch (URISyntaxException ex) {
            return null;
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
}
