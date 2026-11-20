package com.github.oinsio.gnomish.httpegress;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * The bytes a {@link BoundedBody} has read so far, held as the chunks the client handed over (FR15,
 * design D16 of migrate-verify-to-executor-contract). A tail read drops whole chunks from the front
 * and advances the first one past the excess, so keeping the last bytes costs the chunk that arrived,
 * never a copy of everything held: a log of hundreds of megabytes read as a 16 MiB tail is copied once
 * per byte, on the client's selector thread, and held at most at the bound plus one chunk.
 *
 * <p>Not thread-safe: the client delivers a body's chunks serially (Reactive Streams §1.3).
 *
 * <p>Implements FR15, NFR-R5 of migrate-verify-to-executor-contract.
 */
final class BodyChunks {

    private final Deque<ByteBuffer> chunks = new ArrayDeque<>();
    private long size;

    /**
     * Appends a copy of the chunk's remaining bytes; the client may reuse the buffer it handed over.
     *
     * @param item the chunk, read to its limit
     */
    void append(ByteBuffer item) {
        ByteBuffer copy = ByteBuffer.allocate(item.remaining());
        copy.put(item).flip();
        chunks.addLast(copy);
        size += copy.remaining();
    }

    /**
     * The number of bytes held.
     *
     * @return the count
     */
    long size() {
        return size;
    }

    /**
     * Keeps only the last {@code limit} bytes held, dropping from the front: every chunk the cut
     * consumes whole leaves the deque, and the first one left is advanced past the rest of the cut.
     * The walk advances one chunk per step, so it ends by the deque's own length.
     *
     * @param limit the bytes to keep; positive
     */
    void keepLast(int limit) {
        long excess = size - limit;
        Iterator<ByteBuffer> front = chunks.iterator();
        while (excess > 0) {
            ByteBuffer head = front.next();
            int cut = (int) Math.min(head.remaining(), excess);
            head.position(head.position() + cut);
            excess -= cut;
            size -= cut;
            if (!head.hasRemaining()) {
                front.remove();
            }
        }
    }

    /**
     * The number of chunks held — the retention the tail bound promises: after {@link #keepLast},
     * exactly the chunks the kept bytes span, no consumed chunk left behind.
     *
     * @return the count
     */
    int heldChunks() {
        return chunks.size();
    }

    /**
     * The bytes held, in order, as one array.
     *
     * @return a new array of {@link #size()} bytes
     */
    byte[] toByteArray() {
        ByteBuffer all = ByteBuffer.allocate(Math.toIntExact(size));
        for (ByteBuffer chunk : chunks) {
            all.put(chunk.duplicate());
        }
        return all.array();
    }
}
