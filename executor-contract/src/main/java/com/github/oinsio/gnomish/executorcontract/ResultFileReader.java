package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Function;

/**
 * The one reader of a factory-named result file (FR6 of define-executor-contract; the single-owner
 * table of its design, D10): an executor's result and answer files, the agent's decision file and a
 * command check's findings file are all read here and nowhere else.
 *
 * <p>One reader is one channel: a size cap and the capture family its text is minted in. A read
 * asks its {@link ResultSource} for one byte more than the cap, so a file of exactly the cap is
 * whole and a longer one is {@link ReadResult.Truncated}: the cap is read, never more, and the
 * outcome is what those bytes parse to. The bytes are inert data: decoded as UTF-8 with every
 * malformed sequence replaced, never executed, never written anywhere, never used as a path; the
 * text leaves this class only as an {@link UntrustedText} of the channel's family, and a cut text
 * ends with {@link #truncationMarker()}. Nothing is deleted or rewritten, so a second read of the
 * same file is equal to the first (NFR-R1); removing a consumed file is its consumer's hygiene.
 *
 * <p>A typed read binds through the contract's one JSON binding, {@link ExecutorContractJson}, with
 * trailing content refused; every field the target type declares as {@link UntrustedText} is minted
 * in the channel's family. Any binding failure — not JSON, a missing required field, an invariant a
 * constructor refuses, a JSON {@code null} document — is {@link ReadResult.Malformed}, never an
 * exception and never a default value.
 *
 * <p>Implements FR6, NFR-R1, NFR-S2 of define-executor-contract.
 */
public final class ResultFileReader {

    /** The decision file's cap, on the host and in the task branch alike: one MiB. */
    public static final int DECISION_FILE_CAP_BYTES = 1024 * 1024;

    /** A command check's findings file cap: 256 KiB. */
    public static final int FINDINGS_FILE_CAP_BYTES = 256 * 1024;

    /** The largest cap a channel may declare: 16 MiB, so a whole read stays one in-memory value. */
    public static final int MAX_CAP_BYTES = 16 * 1024 * 1024;

    private final int capBytes;

    private final Function<String, UntrustedText> mint;

    /**
     * A reader of one channel.
     *
     * @param capBytes the most bytes a read takes; from 1 to {@link #MAX_CAP_BYTES}
     * @param mint the capture family of the channel's writer, e.g. {@code UntrustedText::agent}
     * @throws IllegalArgumentException if {@code capBytes} is out of range
     */
    public ResultFileReader(int capBytes, Function<String, UntrustedText> mint) {
        if (capBytes < 1 || capBytes > MAX_CAP_BYTES) {
            throw new IllegalArgumentException(
                    "a result-file cap is 1 to " + MAX_CAP_BYTES + " bytes, got " + capBytes);
        }
        this.capBytes = capBytes;
        this.mint = mint;
    }

    /**
     * The line a cut text ends with, naming the cap it was cut at.
     *
     * @return the marker; never null
     */
    public String truncationMarker() {
        return "\n[truncated: the file is longer than the read cap of " + capBytes + " bytes]";
    }

    /**
     * Reads the file as text: {@link ReadResult.Parsed} with the whole text, {@link
     * ReadResult.Truncated} with the cut and marked text, or {@link ReadResult.Absent}. A text read
     * is never malformed.
     *
     * @param source where the file is read from; never null
     * @return the outcome; never null
     */
    public ReadResult<UntrustedText> readText(ResultSource source) {
        Optional<Snapshot> read = snapshot(source);
        if (read.isEmpty()) {
            return new ReadResult.Absent<>();
        }
        Snapshot snapshot = read.get();
        return outcome(snapshot, new ReadResult.Parsed<>(snapshot.content()));
    }

    /**
     * Reads the file as one JSON document of {@code type}.
     *
     * @param source where the file is read from; never null
     * @param type the document type the content binds to
     * @param <T> the document type
     * @return the outcome; never null
     */
    public <T> ReadResult<T> readJson(ResultSource source, Class<T> type) {
        Optional<Snapshot> read = snapshot(source);
        if (read.isEmpty()) {
            return new ReadResult.Absent<>();
        }
        Snapshot snapshot = read.get();
        return outcome(snapshot, parse(snapshot, type));
    }

    private <T> ReadResult<T> outcome(Snapshot snapshot, ReadResult.Content<T> content) {
        return snapshot.truncated() ? new ReadResult.Truncated<>(capBytes, content) : content;
    }

    private <T> ReadResult.Content<T> parse(Snapshot snapshot, Class<T> type) {
        T value;
        try {
            value = binding(type).readValue(snapshot.text());
        } catch (JsonProcessingException e) {
            return new ReadResult.Malformed<>(mint.apply(e.getOriginalMessage()), snapshot.content());
        }
        if (value == null) {
            return new ReadResult.Malformed<>(
                    UntrustedText.factory("the document is JSON null, not " + type.getSimpleName()),
                    snapshot.content());
        }
        return new ReadResult.Parsed<>(value);
    }

    /**
     * The contract's binding as this reader opens it: one document of {@code type}, trailing content
     * refused, every carrier field minted in the channel's family. Package-private so the module's
     * specs read through the very binding a result read uses.
     */
    ObjectReader binding(Class<?> type) {
        return ExecutorContractJson.reader()
                .forType(type)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .withAttribute(UntrustedTextDeserializer.MINT_ATTRIBUTE, new TextMint(mint));
    }

    private Optional<Snapshot> snapshot(ResultSource source) {
        return source.read(capBytes + 1L).map(bytes -> {
            boolean truncated = bytes.length > capBytes;
            String text = new String(bytes, 0, Math.min(bytes.length, capBytes), StandardCharsets.UTF_8);
            UntrustedText content = mint.apply(truncated ? text + truncationMarker() : text);
            return new Snapshot(text, truncated, content);
        });
    }

    /**
     * One read's bytes as text: {@code text} is what the parser sees, cut at the cap and never
     * marked; {@code content} is the carrier a consumer sees, marked when cut.
     */
    private record Snapshot(String text, boolean truncated, UntrustedText content) {}
}
