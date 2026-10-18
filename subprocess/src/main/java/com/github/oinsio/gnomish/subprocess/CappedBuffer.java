package com.github.oinsio.gnomish.subprocess;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * A {@link Drain}'s buffer behind its capacity: it keeps bytes until the capacity is spent and counts
 * any byte past it as an overflow, dropping it (design D17 of fix-terminal-receipt-convergence). The
 * drain thread writes; a joining reader reads a consistent prefix, since {@link ByteArrayOutputStream}
 * is synchronized and {@code overflowed} is volatile. It also keeps the read failure, if one ended the
 * drain, for a reader that classifies a broken stream.
 *
 * <p>Implements FR19 of fix-terminal-receipt-convergence.
 */
final class CappedBuffer implements DrainSink {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private long remaining;
    private volatile boolean overflowed;
    private volatile @Nullable IOException failure;

    /**
     * @param capacity how many bytes to keep; {@link Long#MAX_VALUE} keeps everything
     */
    CappedBuffer(long capacity) {
        this.remaining = capacity;
    }

    /** Keeps the first {@code length} bytes of {@code chunk} as far as the capacity allows. */
    @Override
    public void keep(byte[] chunk, int length) {
        int kept = (int) Math.min(length, remaining);
        buffer.write(chunk, 0, kept);
        remaining -= kept;
        if (kept < length) {
            overflowed = true;
        }
    }

    byte[] bytes() {
        return buffer.toByteArray();
    }

    String text() {
        return buffer.toString(StandardCharsets.UTF_8);
    }

    boolean overflowed() {
        return overflowed;
    }

    /** Records the read failure that ended the drain before end-of-file. */
    void broke(IOException broken) {
        failure = broken;
    }

    @Nullable
    IOException failure() {
        return failure;
    }
}
