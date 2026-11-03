package com.github.oinsio.gnomish.app.conformance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.oinsio.gnomish.app.describe.ProgramChannel;
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.executorcontract.WireDocument;
import com.github.oinsio.gnomish.executorcontract.WireSchemas;
import com.github.oinsio.gnomish.sandbox.CapabilityPassport;
import com.github.oinsio.gnomish.sandbox.DenialCursor;
import com.github.oinsio.gnomish.sandbox.DenialRead;
import com.github.oinsio.gnomish.sandbox.DenialRestoration;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The conformance kit's watch on the wire (FR24 of define-executor-contract, design D19): one
 * execution environment, decorated. Every document a {@code program} candidate writes reaches the
 * factory through {@link #readFile} on a path the adapter handed the child in a variable, and every
 * request leaves through {@link #exec} as the command's standard input; this is where the kit judges
 * both on the bytes themselves, with the production readers untouched.
 *
 * <p>On {@code exec} it notes, from the command's own variables — the {@link ProgramChannel}
 * constants, never a parsed path — which path will hold a describe answer (the operation word is
 * {@code describe}), a result or an answer; for a {@code start} or {@code poll} it judges the
 * rendered request against the request schema and, when this observation probes, adds the one field
 * {@link #PROBE_FIELD} the contract does not define. On {@code readFile} of a noted path it judges
 * the bytes read against the matching schema file through {@link WireSchemas} and keeps every breach
 * for the case, handing the bytes back unchanged. Every other call — the working-copy check's {@code
 * git status} among them — passes through as it came.
 *
 * <p>Implements FR12, FR24 of define-executor-contract.
 */
@NullMarked
final class WireObservation implements TaskExecutionEnvironment {

    /** The field the probe adds to a request: no executor of protocol 1 knows it. */
    static final String PROBE_FIELD = "x-gnomish-conformance-probe";

    private final TaskExecutionEnvironment delegate;
    private final boolean probe;
    private final Map<String, WireDocument> noted = new ConcurrentHashMap<>();
    private final List<WireBreach> breaches = new CopyOnWriteArrayList<>();
    private volatile @Nullable JsonNode describeDocument;

    /**
     * @param delegate the environment the candidate really runs in; never null
     * @param probe whether a request leaving through this environment carries {@link #PROBE_FIELD}
     */
    WireObservation(TaskExecutionEnvironment delegate, boolean probe) {
        this.delegate = delegate;
        this.probe = probe;
    }

    /** Every breach judged so far, in the order the documents were read; immutable. */
    List<WireBreach> breaches() {
        return List.copyOf(breaches);
    }

    /**
     * The last describe document read through this environment as a plain JSON tree, or {@code
     * null} when none was read or it is no JSON.
     */
    @Nullable
    JsonNode describeDocument() {
        return describeDocument;
    }

    @Override
    public ExecHandle exec(ExecCommand command) {
        Map<String, String> variables = command.env();
        String written = variables.get(ProgramChannel.RESULT_FILE_VARIABLE);
        if (written == null) {
            return delegate.exec(command);
        }
        if (ProgramChannel.DESCRIBE.equals(command.command().getLast())) {
            noted.put(written, WireDocument.DESCRIBE);
            return delegate.exec(command);
        }
        noted.put(written, WireDocument.RESULT);
        String answer = variables.get(ProgramChannel.ANSWER_FILE_VARIABLE);
        if (answer != null) {
            noted.put(answer, WireDocument.ANSWER);
        }
        String request = command.stdin();
        if (request == null) {
            return delegate.exec(command);
        }
        breaches.addAll(WireBreach.of(WireDocument.REQUEST, WireSchemas.judge(WireDocument.REQUEST, request)));
        String sent = probe ? probed(request) : request;
        return delegate.exec(new ExecCommand(command.command(), variables, sent, command.stderr()));
    }

    @Override
    public Optional<byte[]> readFile(String path, long sizeCap) {
        Optional<byte[]> read = delegate.readFile(path, sizeCap);
        WireDocument kind = noted.remove(path);
        if (kind != null && read.isPresent()) {
            String text = new String(read.get(), StandardCharsets.UTF_8);
            if (kind == WireDocument.DESCRIBE) {
                describeDocument = tree(text);
            }
            breaches.addAll(WireBreach.of(kind, WireSchemas.judge(kind, text)));
        }
        return read;
    }

    private static @Nullable JsonNode tree(String text) {
        try {
            return ExecutorContractJson.reader().readTree(text);
        } catch (JsonProcessingException notJson) {
            return null;
        }
    }

    private static String probed(String request) {
        try {
            if (!(ExecutorContractJson.reader().readTree(request) instanceof ObjectNode tree)) {
                throw new IllegalStateException("the factory's own request is no JSON object");
            }
            tree.put(PROBE_FIELD, "a field no executor of protocol 1 knows");
            return ExecutorContractJson.writer().writeValueAsString(tree);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException("the factory's own request is no JSON document", unreadable);
        }
    }

    @Override
    public void materialize(String branch, @Nullable String commitPin) {
        delegate.materialize(branch, commitPin);
    }

    @Override
    public void putFile(String path, byte[] content) {
        delegate.putFile(path, content);
    }

    @Override
    public void harvest() {
        delegate.harvest();
    }

    @Override
    public void dispose() {
        delegate.dispose();
    }

    @Override
    public String scratchRoot() {
        return delegate.scratchRoot();
    }

    @Override
    public CapabilityPassport passport() {
        return delegate.passport();
    }

    @Override
    public void probeGuard() {
        delegate.probeGuard();
    }

    @Override
    public DenialRead readDenials() {
        return delegate.readDenials();
    }

    @Override
    public Optional<DenialCursor> denialCursor() {
        return delegate.denialCursor();
    }

    @Override
    public void restoreDenials(DenialRestoration restoration) {
        delegate.restoreDenials(restoration);
    }
}
