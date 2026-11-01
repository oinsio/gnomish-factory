package com.github.oinsio.gnomish.executorcontract

/**
 * A result source over bytes the spec holds, standing in for an environment's capped channel read:
 * it honours the seam's contract — at most {@code limit} bytes — and records every limit it was
 * asked for, so a spec can see what the reader requested (FR6 of define-executor-contract).
 */
final class CannedSource implements ResultSource {

    private final byte[] bytes

    final List<Long> limits = []

    private CannedSource(byte[] bytes) {
        this.bytes = bytes
    }

    static CannedSource of(String text) {
        new CannedSource(text.getBytes('UTF-8'))
    }

    static CannedSource ofBytes(byte[] bytes) {
        new CannedSource(bytes)
    }

    static CannedSource absent() {
        new CannedSource(null)
    }

    @Override
    Optional<byte[]> read(long limit) {
        limits << limit
        bytes == null ? Optional.empty() : Optional.of(Arrays.copyOf(bytes, (int) Math.min(limit, bytes.length)))
    }
}
