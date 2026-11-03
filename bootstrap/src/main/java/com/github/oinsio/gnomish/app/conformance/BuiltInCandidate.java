package com.github.oinsio.gnomish.app.conformance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.executorcontract.WireDocument;
import com.github.oinsio.gnomish.executorcontract.WireSchemas;
import com.github.oinsio.gnomish.executorcontract.Workspace;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * A built-in executor as the conformance kit drives it (FR8, FR24 of define-executor-contract;
 * design D2, D19): the mirror interface itself, in process — no process is spawned and no document
 * crosses a wire, so the kit's wire-only cases do not apply ({@link #onWire()}). The describe answer
 * is written back through the contract's writer and that document judged against the describe schema
 * through {@link WireSchemas}, exactly as a program's describe file is, and the settings-schema case
 * reads the same document; every other case runs in a directory of its own under the scratch root,
 * named after the case.
 *
 * <p>One executor instance serves every case, as one built-in serves every round of a run: each case's
 * requests name its own working copy, and a pending run's key is the instance's (FR23).
 *
 * <p>The answer counts as accepted: the factory's describe preflight has no in-process path yet — every
 * declaration it describes is a program — so no factory judgement exists to refuse a built-in's
 * answer beyond the two schemas the kit applies itself.
 *
 * <p>Implements FR8, FR12, FR24 of define-executor-contract.
 */
@NullMarked
public final class BuiltInCandidate implements KitSubject {

    private final Executor executor;
    private final Path scratchRoot;

    /**
     * @param executor the built-in, driven through the mirror interface alone; never null
     * @param scratchRoot an existing, empty directory the cases' working copies are made in; never null
     */
    public BuiltInCandidate(Executor executor, Path scratchRoot) {
        this.executor = executor;
        this.scratchRoot = scratchRoot;
    }

    @Override
    public boolean onWire() {
        return false;
    }

    @Override
    public Described describe() {
        Describe answer;
        try {
            answer = executor.describe();
        } catch (RuntimeException e) {
            return new Described(null, null, List.of(), List.of(CaseJudgement.threw("Executor.describe", e)));
        }
        String written = written(answer);
        return new Described(
                answer,
                tree(written),
                WireBreach.of(WireDocument.DESCRIBE, WireSchemas.judge(WireDocument.DESCRIBE, written)),
                List.of());
    }

    @Override
    public CaseRun open(KitCase kase, Describe describe) {
        return new BuiltInRun(
                executor,
                new Workspace(CaseDirectories.create(scratchRoot, kase).toString()));
    }

    private static String written(Describe answer) {
        try {
            return ExecutorContractJson.writer().writeValueAsString(answer);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the contract's writer could not write a describe answer", e);
        }
    }

    private static JsonNode tree(String written) {
        try {
            return ExecutorContractJson.reader().readTree(written);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the contract's writer wrote a describe answer it cannot read", e);
        }
    }

    /** One case's run of the built-in: the shared executor and the case's own working copy. */
    private record BuiltInRun(Executor executor, Workspace workspace) implements CaseRun {

        @Override
        public List<WireBreach> violations() {
            return List.of();
        }

        @Override
        public void close() {
            // Nothing to release: an in-process run holds no environment of its own.
        }
    }
}
