package com.simon.voiceime;

import java.io.ByteArrayOutputStream;

/** Prefix for stream/preview only. Full audio belongs to VoicePendingQueue on disk. */
final class BoundedPcmBuffer extends ByteArrayOutputStream {
    private final int limit;
    private boolean truncated;
    BoundedPcmBuffer(int limit) { super(Math.min(limit, 16384)); this.limit = limit; }
    @Override public synchronized void write(byte[] data, int offset, int length) {
        int keep = Math.min(length, limit - count);
        if (keep < length) truncated = true;
        if (keep > 0) super.write(data, offset, keep);
    }
    @Override public synchronized void write(int value) {
        if (count < limit) super.write(value); else truncated = true;
    }
    @Override public synchronized void reset() { super.reset(); truncated = false; }
    synchronized boolean truncated() { return truncated; }
}
