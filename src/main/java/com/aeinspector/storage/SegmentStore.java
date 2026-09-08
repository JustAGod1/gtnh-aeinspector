package com.aeinspector.storage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Synchronous server-thread storage. No executors, futures or background IO. */
public final class SegmentStore implements AutoCloseable {
    private static final int MAGIC = 0x41454953;
    private static final int VERSION = 1;
    private final Path root;
    private final int maxSegmentBytes;
    private final long cacheLimit;
    private final LinkedHashMap<String, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true);
    private long cacheBytes;
    private boolean closed;

    public SegmentStore(Path root, int maxSegmentBytes, long cacheLimit) throws IOException {
        if (maxSegmentBytes < 1 || cacheLimit < 0) throw new IllegalArgumentException("Invalid storage budgets");
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        this.maxSegmentBytes = maxSegmentBytes;
        this.cacheLimit = cacheLimit;
    }

    public SegmentStore(Path root) throws IOException { this(root, 32 * 1024 * 1024, 128L * 1024 * 1024); }

    private Path path(String key) {
        if (!key.matches("[a-zA-Z0-9_-]{1,120}")) throw new IllegalArgumentException("Invalid segment key");
        return root.resolve(key + ".aeis");
    }

    public void write(String key, byte[] payload) throws IOException {
        checkOpen();
        Path target = path(key);
        if (payload.length > maxSegmentBytes) throw new IOException("Segment exceeds size limit");
        Path temporary = Files.createTempFile(root, key + "-", ".tmp");
        try {
            CRC32 crc = new CRC32();
            crc.update(payload);
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                    new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary), 65536), 65536)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(payload.length);
                out.writeLong(crc.getValue());
                out.write(payload);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            putCache(key, payload.clone());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public byte[] read(String key) throws IOException {
        checkOpen();
        Path source = path(key);
        byte[] data = cache.get(key);
        if (data != null) return data.clone();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new BufferedInputStream(Files.newInputStream(source), 65536), 65536)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported segment");
            int length = in.readInt();
            if (length < 0 || length > maxSegmentBytes) throw new IOException("Invalid segment size");
            long expected = in.readLong();
            data = new byte[length];
            in.readFully(data);
            if (in.read() != -1) throw new IOException("Trailing segment data");
            CRC32 crc = new CRC32();
            crc.update(data);
            if (crc.getValue() != expected) throw new IOException("Segment checksum mismatch");
            putCache(key, data);
            return data.clone();
        }
    }

    private void putCache(String key, byte[] value) {
        byte[] old = cache.remove(key);
        if (old != null) cacheBytes -= old.length;
        if (value.length > cacheLimit) return;
        cache.put(key, value);
        cacheBytes += value.length;
        Iterator<Map.Entry<String, byte[]>> i = cache.entrySet().iterator();
        while (cacheBytes > cacheLimit && i.hasNext()) {
            cacheBytes -= i.next().getValue().length;
            i.remove();
        }
    }

    public long cachedBytes() { return cacheBytes; }

    private void checkOpen() throws IOException {
        if (closed) throw new IOException("Segment store closed");
    }

    @Override
    public void close() {
        closed = true;
        cache.clear();
        cacheBytes = 0;
    }
}
