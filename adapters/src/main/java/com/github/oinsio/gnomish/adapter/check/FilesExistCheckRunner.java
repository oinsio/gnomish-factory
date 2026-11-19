package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.adapter.pipeline.PathSafety;
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.gitobjects.InvalidTreePathException;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The real {@code files_exist} existence judgement (design D2): checks that every literal
 * workspace-relative path in the {@code files} setting exists, answering the missing paths, which
 * {@link FilesExistExecutor} renders as one {@code error} SARIF result per path within the result cap
 * {@link FilesExistOutcome#reported} applies (D5 of migrate-verify-to-executor-contract: the
 * executor answers, the engine's {@code VerdictDerivation} derives the verdict against the check's
 * threshold). Malformed settings or a path resolving outside the workspace root never reach an
 * existence check — they yield {@link FilesExistOutcome.Unanswerable} instead, since no verdict
 * about the artifact's quality can be trusted from an unsafe or unparseable input.
 *
 * <p>Two legs, one runner for every environment adapter (FR21, D15 of
 * add-sandbox-core): over a {@link DirectoryWorkspace} (host modes) existence
 * is the workspace filesystem, as today; over an {@link RecordedAttemptCommitWorkspace}
 * (sandboxed mode) existence is answered from the harvested attempt commit's
 * tree via bare git object reads in the factory clone — no environment access,
 * so uncommitted box residue never counts. The factory-clone reader is bound
 * per run through {@link #withAttemptReader}; the sandbox integration pass
 * wires it alongside the workspace itself.
 *
 * <p>The existence judgement is {@link #probe}, made once; {@link FilesExistExecutor} wraps the
 * runner as the {@code files-exist} executor of the executor contract (FR1 of
 * migrate-verify-to-executor-contract), its one rendering — the engine-port rendering left with
 * the engine ports (task 5.2, design D9 of that change). The settings are read by {@link
 * FilesExistSettings}.
 *
 * <p>Implements FR6 of add-manual-run; FR21, D15 of add-sandbox-core; FR1, FR7 of
 * migrate-verify-to-executor-contract.
 *
 * <p>A plain class, not a record: PIT's JVMTI-based hot-swap mutation testing refuses record
 * bytecode changes (hcoles/pitest#1285) — a record shape here would turn nearly every mutation of
 * {@link #probe} into an unkillable RUN_ERROR instead of the one isolated, documented
 * exception {@link #opaqueWorkspace} carries.
 */
public final class FilesExistCheckRunner {

    private final @Nullable GitObjects attemptReader;

    /** The host-modes runner: filesystem existence only, no factory-clone reader bound. */
    public FilesExistCheckRunner() {
        this(null);
    }

    private FilesExistCheckRunner(@Nullable GitObjects attemptReader) {
        this.attemptReader = attemptReader;
    }

    /**
     * Returns a copy of this runner whose sandboxed leg answers existence from {@code
     * attemptReader} — the factory clone the attempt commit was harvested into (FR21, D15 of
     * add-sandbox-core). Mirrors {@code ShellCommandCheckRunner.withChildEnv}'s per-run rebind
     * pattern.
     *
     * @param attemptReader the factory clone's bare-object reader; never null
     * @return a runner identical but for the bound reader; never null
     */
    public FilesExistCheckRunner withAttemptReader(GitObjects attemptReader) {
        return new FilesExistCheckRunner(attemptReader);
    }

    /**
     * Judges existence of every path the {@code files} setting lists against {@code workspace}: the
     * one existence judgement the executor contract's {@link FilesExistExecutor} renders.
     *
     * @param settings the check's settings as plain values; never null
     * @param workspace the working copy the paths are judged in; never null
     * @return the missing paths, or why none could be judged; never null
     */
    FilesExistOutcome probe(Map<String, ?> settings, Workspace workspace) {
        List<String> files;
        try {
            files = FilesExistSettings.files(settings);
        } catch (FilesExistSettings.MalformedSettingsException e) {
            return new FilesExistOutcome.Unanswerable(UntrustedText.manifest(e.reason()));
        }

        if (workspace instanceof RecordedAttemptCommitWorkspace attemptWorkspace) {
            return probeAttemptCommit(files, attemptWorkspace);
        }
        if (!(workspace instanceof DirectoryWorkspace directoryWorkspace)) {
            return opaqueWorkspace(workspace);
        }

        Path root = directoryWorkspace.root();
        List<String> missing = new ArrayList<>();
        for (String file : files) {
            PathSafety.Resolution resolution = PathSafety.resolveWithinRoot(root, file);
            if (resolution instanceof PathSafety.Escapes(String ref)) {
                return new FilesExistOutcome.Unanswerable(
                        UntrustedText.manifest("files_exist path escapes the workspace: " + ref));
            }
            PathSafety.Within within = (PathSafety.Within) resolution;
            if (!Files.exists(within.path())) {
                missing.add(file);
            }
        }
        return new FilesExistOutcome.Missing(missing);
    }

    /**
     * The sandboxed leg (FR21, D15): existence is the attempt commit's tree, read as bare git
     * objects in the factory clone — one implementation for every environment adapter, no
     * per-adapter builtin code and no untrusted in-box answers. A path refused by the library's
     * tree-path validation (absolute, {@code ..}, {@code .git}) is the sandboxed twin of the
     * host leg's workspace-escape refusal.
     */
    private FilesExistOutcome probeAttemptCommit(List<String> files, RecordedAttemptCommitWorkspace workspace) {
        if (attemptReader == null) {
            return new FilesExistOutcome.Unanswerable(
                    UntrustedText.factory("files_exist has no factory-clone reader bound for the sandboxed workspace"));
        }
        ObjectId commit = ObjectId.of(workspace.attemptCommitSha());
        List<String> missing = new ArrayList<>();
        for (String file : files) {
            try {
                if (!attemptReader.exists(commit, file)) {
                    missing.add(file);
                }
            } catch (InvalidTreePathException e) {
                return new FilesExistOutcome.Unanswerable(
                        UntrustedText.manifest("files_exist path escapes the workspace: " + file));
            }
        }
        return new FilesExistOutcome.Missing(missing);
    }

    /**
     * The outcome for a workspace that is neither a {@link DirectoryWorkspace} nor a {@link
     * RecordedAttemptCommitWorkspace}, naming its actual runtime type.
     *
     * <p>PIT M4 documented exception (build.gradle has the full rationale):
     * {@code @DoNotMutate} — the same JVMTI RedefineClasses/record-attribute
     * restriction as the class javadoc above (hcoles/pitest#1285) — NO_COVERAGE,
     * not a real gap: "a workspace that is not a DirectoryWorkspace is
     * failed/infrastructure naming its runtime type" in FilesExistExecutorSpec exercises it
     * and passes. Isolated to its own method so the rest of {@link #probe} stays
     * under the 100% mutation gate.
     */
    @DoNotMutate
    private static FilesExistOutcome opaqueWorkspace(Workspace workspace) {
        return new FilesExistOutcome.Unanswerable(
                UntrustedText.factory("files_exist requires a DirectoryWorkspace, got "
                        + workspace.getClass().getName()));
    }
}
