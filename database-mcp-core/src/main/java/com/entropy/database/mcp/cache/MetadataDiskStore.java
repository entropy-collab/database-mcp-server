/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.entropy.database.mcp.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * JSON file persistence for the metadata half of {@link DatabaseCacheImpl}.
 *
 * <p>Schema metadata is expensive to fetch and almost never changes, but a heap-only cache throws
 * all of it away on every restart: the first {@code listTables} / {@code describeTable} after a
 * deploy pays full dictionary-view cost again, once per connection. This store keeps a copy on
 * disk so a restart resumes warm.
 *
 * <p><b>Writes are batched, not synchronous.</b> {@link #markDirty()} only sets a flag; a single
 * background thread flushes at most once per {@code flushInterval}. Every {@code putMetadata} is a
 * cache write on a request path, and fsyncing one file per entry would put disk latency inside
 * tool calls for a cache whose whole purpose is to avoid latency. The cost of batching is that up
 * to one interval of newly cached metadata is lost on a hard kill — which is exactly the state the
 * process was in before this class existed, so it can only be an improvement.
 *
 * <p><b>Values round-trip through JSON, not Java serialization.</b> Metadata rows come from
 * {@code JdbcTemplate.queryForList}, so a value can hold driver types ({@code Timestamp},
 * {@code BigDecimal}, {@code oracle.sql.*}). Those reload as JSON primitives — a number or a
 * string, not the original class. Every consumer of the metadata cache reads these values as
 * {@code List}/{@code Map}/{@code String} and renders them into MCP responses, so the shape
 * survives; an exact type does not. That is the reason persistence is opt-in.
 *
 * <p>One file per connection ({@code <sanitized-connection>-<hash>.json}), so dropping a
 * connection's cache is deleting a file rather than rewriting a shared one. Connection names reach
 * the filesystem here, so the name is sanitized and disambiguated by hash instead of being used as
 * a path component directly.
 */
public class MetadataDiskStore {

    private static final Logger log = LoggerFactory.getLogger(MetadataDiskStore.class);

    /** Filename stem for entries cached without a connection scope. */
    private static final String UNSCOPED_FILE_STEM = "_unscoped";
    private static final String FILE_SUFFIX = ".json";
    /** Bound on the sanitized part of a filename; the hash suffix carries the uniqueness. */
    private static final int MAX_STEM_LENGTH = 64;

    private final Path baseDir;
    private final Duration flushInterval;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean dirty = new AtomicBoolean();

    private ScheduledExecutorService flusher;
    private Supplier<Map<String, Object>> snapshotSupplier;

    public MetadataDiskStore(Path baseDir, Duration flushInterval) {
        this.baseDir = baseDir;
        this.flushInterval = flushInterval;
    }

    /**
     * Every persisted entry, keyed exactly as {@link DatabaseCacheImpl} keys it internally
     * (connection scope prefix included). Returns an empty map when nothing is persisted yet or
     * when the directory cannot be read — a cache that fails to prewarm is a cold cache, not a
     * failed startup.
     */
    public Map<String, Object> load() {
        if (!Files.isDirectory(baseDir)) {
            return Map.of();
        }
        Map<String, Object> loaded = new HashMap<>();
        List<Path> files;
        try (var stream = Files.list(baseDir)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(FILE_SUFFIX)).toList();
        } catch (IOException e) {
            log.warn("Could not list the metadata cache directory {}; starting with a cold cache", baseDir, e);
            return Map.of();
        }
        for (Path file : files) {
            readFile(file, loaded);
        }
        if (!loaded.isEmpty()) {
            log.info("Metadata cache prewarmed from disk: {} entries from {} file(s) under {}",
                    loaded.size(), files.size(), baseDir);
        }
        return loaded;
    }

    private void readFile(Path file, Map<String, Object> into) {
        try {
            ScopeFile content = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeReference<>() { });
            if (content == null || content.entries == null) {
                return;
            }
            String scopePrefix = content.connection == null || content.connection.isEmpty()
                    ? ""
                    : content.connection + DatabaseCacheImpl.SCOPE_SEPARATOR;
            content.entries.forEach((key, value) -> into.put(scopePrefix + key, value));
        } catch (IOException | RuntimeException e) {
            // A file written by an older layout, or truncated by a kill mid-write, must not stop the
            // other connections from prewarming.
            log.warn("Skipping unreadable metadata cache file {}", file, e);
        }
    }

    /**
     * Begin flushing. {@code snapshotSupplier} must return the live metadata entries keyed the way
     * {@link #load()} returns them.
     */
    public synchronized void start(Supplier<Map<String, Object>> snapshotSupplier) {
        if (flusher != null) {
            return;
        }
        this.snapshotSupplier = snapshotSupplier;
        this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metadata-disk-store");
            // Daemon: this thread must never be the reason the JVM stays alive. shutdown() does the
            // final flush, and a flush that has not happened yet only costs cache warmth.
            t.setDaemon(true);
            return t;
        });
        long intervalMs = Math.max(flushInterval.toMillis(), 1000);
        flusher.scheduleWithFixedDelay(this::flushIfDirty, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("Metadata cache disk persistence enabled: dir={}, flushInterval={}ms", baseDir, intervalMs);
    }

    /** Mark the persisted copy stale. Cheap enough to call from any cache write. */
    public void markDirty() {
        dirty.set(true);
    }

    private void flushIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            try {
                flush();
            } catch (RuntimeException e) {
                // Losing a flush degrades the next restart to a cold cache; it must not kill the
                // scheduled task, which scheduleWithFixedDelay would do on a thrown exception.
                log.warn("Metadata cache flush to {} failed; will retry on the next change", baseDir, e);
            }
        }
    }

    /**
     * Write the current snapshot out now. Groups entries by connection scope and replaces each
     * connection's file atomically.
     */
    public void flush() {
        Supplier<Map<String, Object>> supplier = this.snapshotSupplier;
        if (supplier == null) {
            return;
        }
        Map<String, Object> snapshot = supplier.get();
        Map<String, ScopeFile> byScope = new LinkedHashMap<>();
        snapshot.forEach((scopedKey, value) -> {
            int sep = scopedKey.indexOf(DatabaseCacheImpl.SCOPE_SEPARATOR);
            String connection = sep < 0 ? "" : scopedKey.substring(0, sep);
            String key = sep < 0 ? scopedKey : scopedKey.substring(sep + 1);
            byScope.computeIfAbsent(connection, c -> {
                ScopeFile f = new ScopeFile();
                f.connection = c;
                f.entries = new LinkedHashMap<>();
                return f;
            }).entries.put(key, value);
        });

        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            log.warn("Could not create the metadata cache directory {}; persistence is inactive", baseDir, e);
            return;
        }
        // Scopes that went empty since the last flush would otherwise keep their stale file forever.
        deleteFilesNotIn(byScope.keySet());
        byScope.forEach(this::writeScopeFile);
    }

    private void deleteFilesNotIn(java.util.Set<String> liveConnections) {
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (String connection : liveConnections) {
            keep.add(fileFor(connection).getFileName().toString());
        }
        try (var stream = Files.list(baseDir)) {
            for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(FILE_SUFFIX)).toList()) {
                if (!keep.contains(file.getFileName().toString())) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException e) {
            log.debug("Could not prune stale metadata cache files under {}", baseDir, e);
        }
    }

    private void writeScopeFile(String connection, ScopeFile content) {
        Path target = fileFor(connection);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            // Write-then-move: a kill during the write leaves the previous complete file in place
            // rather than a half-written one that load() would have to discard.
            Files.writeString(temp, mapper.writeValueAsString(content), StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not persist the metadata cache for connection [{}] to {}", connection, target, e);
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                log.debug("Leftover temp file {} could not be removed", temp);
            }
        }
    }

    /** Flush once more and stop the background thread. */
    public synchronized void shutdown() {
        if (flusher == null) {
            return;
        }
        flusher.shutdownNow();
        flusher = null;
        try {
            flush();
        } catch (RuntimeException e) {
            log.warn("Final metadata cache flush to {} failed", baseDir, e);
        }
        snapshotSupplier = null;
    }

    /**
     * Filename for a connection scope. A connection name is caller-supplied, so it is reduced to
     * safe characters and disambiguated by a hash of the original rather than used as a path
     * component — otherwise {@code ../../etc/x} would be a valid "connection name".
     */
    Path fileFor(String connection) {
        if (connection == null || connection.isEmpty()) {
            return baseDir.resolve(UNSCOPED_FILE_STEM + FILE_SUFFIX);
        }
        StringBuilder stem = new StringBuilder(connection.length());
        for (int i = 0; i < connection.length() && stem.length() < MAX_STEM_LENGTH; i++) {
            char c = connection.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            stem.append(safe ? c : '_');
        }
        return baseDir.resolve(stem + "-" + shortHash(connection) + FILE_SUFFIX);
    }

    private static String shortHash(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    /** On-disk shape: the connection this file belongs to, plus its unprefixed metadata entries. */
    static final class ScopeFile {
        public String connection;
        public Map<String, Object> entries;
    }
}
