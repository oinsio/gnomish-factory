package com.github.oinsio.gnomish.executorcontract;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * What one read of a factory-named result file produced (FR6 of define-executor-contract): the
 * typed value, the reason the content is not one, no file at all, or a file over the read cap
 * together with what its first {@code capBytes} bytes parsed to. The read is the only judge; a
 * consumer maps the outcome to its own fallback — a stand-in question, a synthetic finding, a
 * {@code failed/infrastructure} result — and never re-reads the file to decide.
 *
 * <p>Every outcome is a value: reading the same file twice yields two equal results (NFR-R1), so
 * recovery after a restart decides exactly as the first pickup did.
 *
 * <p>Implements FR6, NFR-R1, NFR-S2 of define-executor-contract.
 *
 * @param <T> the type a typed read binds the content to; {@link UntrustedText} for a text read
 */
public sealed interface ReadResult<T> {

    /** What the bytes that were read parsed to: a value, or the reason they are not one. */
    sealed interface Content<T> extends ReadResult<T> permits Parsed, Malformed {}

    /** The content is a {@code T}; every text field of it typed {@link UntrustedText} is a carrier. */
    record Parsed<T>(T value) implements Content<T> {}

    /**
     * The content is no {@code T}. {@code reason} names what is wrong in the parser's words, which
     * quote the content and so travel as a carrier; {@code content} is the whole text that was read
     * (marked when it was cut at the cap), for a consumer whose fallback is the raw text itself.
     * An empty file is malformed with blank content, never absent.
     */
    record Malformed<T>(UntrustedText reason, UntrustedText content) implements Content<T> {}

    /** No file exists at the path: the executor wrote none. */
    record Absent<T>() implements ReadResult<T> {}

    /**
     * The file is longer than {@code capBytes}: only that many bytes were read, and {@code content}
     * is what they parsed to. The run resolves per that outcome, never by reading further.
     */
    record Truncated<T>(int capBytes, Content<T> content) implements ReadResult<T> {}
}
