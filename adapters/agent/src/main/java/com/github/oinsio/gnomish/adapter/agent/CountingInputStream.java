package com.github.oinsio.gnomish.adapter.agent;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts the raw bytes read through it (design D5 of fix-round-stdout-drain):
 * {@link StreamDrain} wraps the process's stdout in one of these underneath the
 * decoding reader, so a round that ends without a result event can report how
 * much of the stream it actually consumed — and whether that volume sits at an
 * OS pipe-buffer boundary, the tripwire for a truncated stream (FR5, UX2).
 *
 * <p>The count is an {@link AtomicLong} rather than a plain field because the
 * drain thread writes it while the round thread reads it when shaping the
 * missing-result diagnostic.
 *
 * <p>Implements FR5, D5 of fix-round-stdout-drain.
 */
final class CountingInputStream extends FilterInputStream {

    private final AtomicLong count = new AtomicLong();

    CountingInputStream(InputStream delegate) {
        super(delegate);
    }

    /** The number of bytes read through this stream so far; never negative. */
    long count() {
        return count.get();
    }

    @Override
    public int read() throws IOException {
        int read = in.read();
        if (read != -1) {
            count.incrementAndGet();
        }
        return read;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = in.read(buffer, offset, length);
        if (read != -1) {
            count.addAndGet(read);
        }
        return read;
    }
}
