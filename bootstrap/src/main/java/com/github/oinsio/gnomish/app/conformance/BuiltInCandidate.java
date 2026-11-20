package com.github.oinsio.gnomish.app.conformance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson;
import com.github.oinsio.gnomish.executorcontract.Role;
import com.github.oinsio.gnomish.executorcontract.WireDocument;
import com.github.oinsio.gnomish.executorcontract.WireSchemas;
import com.github.oinsio.gnomish.executorcontract.Workspace;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A built-in executor as the conformance kit drives it (FR8, FR24 of define-executor-contract;
 * design D2, D19): the mirror interface itself, in process — no process is spawned and no document
 * crosses a wire, so the kit's wire-only cases do not apply ({@link #onWire()}). The describe answer
 * is written back through the contract's writer and that document judged against the describe schema
 * through {@link WireSchemas}, exactly as a program's describe file is, and the settings-schema case
 * reads the same document; every other case runs in a directory of its own under the scratch root,
 * named after the case.
 *
 * <p>The candidate is driven through the built-in's registry entry (design D18 of
 * migrate-verify-to-executor-contract): it takes the entry's describe answer and a per-case binding,
 * so each case's executor is bound to that case's own working copy — the one a built-in judges, since
 * it reads the workspace it was bound to and never the request's path — and a pending case's polls go
 * to an executor of the same entry bound to the case's poll copy, as the engine binds one per request
 * (design D11 as amended 2026-11-14). It carries the settings of its ordinary cases, which the
 * settings-schema case judges too, so a built-in whose schema requires a key is run, not reported not
 * applicable.
 *
 * <p>The answer counts as accepted: the factory's describe preflight has no in-process path yet — every
 * declaration it describes is a program — so no factory judgement exists to refuse a built-in's
 * answer beyond the two schemas the kit applies itself.
 *
 * <p>Implements FR8, FR12, FR24 of define-executor-contract; FR1, FR8, FR13 of
 * migrate-verify-to-executor-contract.
 */
@NullMarked
public final class BuiltInCandidate implements KitSubject {

    private final Describe answer;
    private final CaseBinding binding;
    private final Map<String, Object> settings;
    private final Path scratchRoot;

    /**
     * @param answer the built-in's describe answer, as its registry entry carries it; never null
     * @param binding the entry's binding of one case's executor to its working copy; never null
     * @param settings the settings its ordinary cases send; never null, possibly empty
     * @param scratchRoot an existing, empty directory the cases' working copies are made in; never null
     */
    public BuiltInCandidate(Describe answer, CaseBinding binding, Map<String, ?> settings, Path scratchRoot) {
        this.answer = answer;
        this.binding = binding;
        this.settings = Map.<String, Object>copyOf(settings);
        this.scratchRoot = scratchRoot;
    }

    @Override
    public boolean onWire() {
        return false;
    }

    @Override
    public Map<String, Object> settings() {
        return settings;
    }

    @Override
    public Described describe() {
        String written = written(answer);
        return new Described(
                answer,
                tree(written),
                WireBreach.of(WireDocument.DESCRIBE, WireSchemas.judge(WireDocument.DESCRIBE, written)),
                List.of());
    }

    @Override
    public CaseRun open(KitCase kase, Describe describe) {
        Path workingCopy = CaseDirectories.create(scratchRoot, kase);
        return new BuiltInRun(kase, workingCopy, binding.bind(kase, workingCopy));
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

    /**
     * One case's run of the built-in: {@code start}'s executor bound to the case's own working copy, and
     * the poll's bound to the case's poll copy ({@link PollCopy}), made on the first poll — whose content
     * before {@code start} is the empty directory the binding is handed — and the same one after it.
     */
    private final class BuiltInRun implements CaseRun {

        private final KitCase kase;
        private final Path workingCopy;
        private final Executor executor;
        private @Nullable PollBinding poll;

        BuiltInRun(KitCase kase, Path workingCopy, Executor executor) {
            this.kase = kase;
            this.workingCopy = workingCopy;
            this.executor = executor;
        }

        @Override
        public Executor executor() {
            return executor;
        }

        @Override
        public Workspace workspace() {
            return new Workspace(workingCopy.toString());
        }

        @Override
        public PollBinding poll(Role role) {
            PollBinding made = poll;
            if (made == null) {
                Path copy = PollCopy.create(scratchRoot, kase, role, workingCopy, empty -> {});
                made = new PollBinding(binding.bind(kase, copy), new Workspace(copy.toString()));
                poll = made;
            }
            return made;
        }

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
