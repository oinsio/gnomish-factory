package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.adapter.pipeline.PathSafety;
import com.github.oinsio.gnomish.adapter.workspace.AttemptCommitWorkspace;
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.domain.engine.port.BuiltinCheckRunner;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.gitobjects.InvalidTreePathException;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The real {@code files_exist} built-in check runner (design D2): checks that
 * every literal workspace-relative path in the {@code files} param exists,
 * collecting one {@link Finding} per missing path. Malformed params or a
 * path resolving outside the workspace root never reach an existence check —
 * they yield {@link Verdict.CannotVerify} instead, since no verdict about the
 * artifact's quality can be trusted from an unsafe or unparseable input.
 *
 * <p>Two legs, one runner for every environment adapter (FR21, D15 of
 * add-sandbox-core): over a {@link DirectoryWorkspace} (host modes) existence
 * is the workspace filesystem, as today; over an {@link AttemptCommitWorkspace}
 * (sandboxed mode) existence is answered from the harvested attempt commit's
 * tree via bare git object reads in the factory clone — no environment access,
 * so uncommitted box residue never counts. The factory-clone reader is bound
 * per run through {@link #withAttemptReader}; the sandbox integration pass
 * wires it alongside the workspace itself.
 *
 * <p>Implements FR6 of add-manual-run; FR21, D15 of add-sandbox-core.
 *
 * <p>A plain class, not a record: PIT's JVMTI-based hot-swap mutation testing refuses record
 * bytecode changes (hcoles/pitest#1285) — a record shape here would turn nearly every mutation of
 * {@link #run}/{@link #runAgainstAttemptCommit}/{@link #describeType} into an unkillable RUN_ERROR
 * instead of the one isolated, documented exception {@link #opaqueWorkspaceVerdict} carries.
 */
public final class FilesExistCheckRunner implements BuiltinCheckRunner {

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

    @Override
    public Verdict run(VerifyCheck.Builtin check, Workspace workspace) {
        List<String> files;
        try {
            files = readFiles(check.params());
        } catch (MalformedParamsException e) {
            return new Verdict.CannotVerify(e.reason(), "");
        }

        if (workspace instanceof AttemptCommitWorkspace attemptWorkspace) {
            return runAgainstAttemptCommit(files, attemptWorkspace);
        }
        if (!(workspace instanceof DirectoryWorkspace directoryWorkspace)) {
            return opaqueWorkspaceVerdict(workspace);
        }

        Path root = directoryWorkspace.root();
        List<Finding> findings = new ArrayList<>();
        for (String file : files) {
            PathSafety.Resolution resolution = PathSafety.resolveWithinRoot(root, file);
            if (resolution instanceof PathSafety.Escapes(String ref)) {
                return new Verdict.CannotVerify("files_exist path escapes the workspace: " + ref, "");
            }
            PathSafety.Within within = (PathSafety.Within) resolution;
            if (!Files.exists(within.path())) {
                findings.add(new Finding("missing file: " + file, file, null));
            }
        }

        return findings.isEmpty() ? new Verdict.Pass() : new Verdict.Fail(findings);
    }

    /**
     * The sandboxed leg (FR21, D15): existence is the attempt commit's tree, read as bare git
     * objects in the factory clone — one implementation for every environment adapter, no
     * per-adapter builtin code and no untrusted in-box answers. A path refused by the library's
     * tree-path validation (absolute, {@code ..}, {@code .git}) is the sandboxed twin of the
     * host leg's workspace-escape refusal.
     */
    private Verdict runAgainstAttemptCommit(List<String> files, AttemptCommitWorkspace workspace) {
        if (attemptReader == null) {
            return new Verdict.CannotVerify(
                    "files_exist has no factory-clone reader bound for the sandboxed workspace", "");
        }
        ObjectId commit = ObjectId.of(workspace.attemptCommitSha());
        List<Finding> findings = new ArrayList<>();
        for (String file : files) {
            try {
                if (!attemptReader.exists(commit, file)) {
                    findings.add(new Finding("missing file: " + file, file, null));
                }
            } catch (InvalidTreePathException e) {
                return new Verdict.CannotVerify("files_exist path escapes the workspace: " + file, "");
            }
        }
        return findings.isEmpty() ? new Verdict.Pass() : new Verdict.Fail(findings);
    }

    /**
     * Builds the {@link Verdict.CannotVerify} for a non-{@link DirectoryWorkspace}
     * argument, naming the workspace's actual runtime type.
     *
     * <p>PIT M4 documented exception (build.gradle has the full rationale):
     * {@code @DoNotMutate} — the same JVMTI RedefineClasses/record-attribute
     * restriction as the class javadoc above (hcoles/pitest#1285) — NO_COVERAGE,
     * not a real gap: "a workspace that is not a DirectoryWorkspace yields
     * CannotVerify" in FilesExistCheckRunnerSpec exercises this method directly
     * and passes. Isolated to its own method so the rest of {@link #run} stays
     * under the 100% mutation gate.
     */
    @DoNotMutate
    private static Verdict opaqueWorkspaceVerdict(Workspace workspace) {
        return new Verdict.CannotVerify(
                "files_exist requires a DirectoryWorkspace, got "
                        + workspace.getClass().getName(),
                "");
    }

    /**
     * Reads and validates the {@code files} param as a list of non-null strings,
     * throwing {@link MalformedParamsException} for anything else — a missing
     * key, a non-list value, or a list with a non-string entry.
     */
    private static List<String> readFiles(Map<String, Object> params) {
        Object rawFiles = params.get("files");
        if (rawFiles == null) {
            throw new MalformedParamsException("files_exist requires a 'files' param");
        }
        if (!(rawFiles instanceof List<?> list)) {
            throw new MalformedParamsException("files_exist 'files' param must be a list, got "
                    + rawFiles.getClass().getName());
        }
        List<String> files = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (!(entry instanceof String stringEntry)) {
                throw new MalformedParamsException(
                        "files_exist 'files' entries must all be strings, found " + describeType(entry));
            }
            files.add(stringEntry);
        }
        return files;
    }

    /**
     * Describes {@code entry}'s runtime type for a malformed-params message, tolerating a
     * {@code null} list entry — a genuine possibility for externally supplied {@code files}
     * params (e.g. a YAML/JSON {@code null} literal in the list), even though the surrounding
     * loop variable's inferred type is non-null.
     */
    private static String describeType(@Nullable Object entry) {
        return entry == null ? "null" : entry.getClass().getName();
    }

    /** Signals malformed {@code files_exist} params; caught locally and turned into CannotVerify. */
    private static final class MalformedParamsException extends RuntimeException {

        private final String reason;

        MalformedParamsException(String reason) {
            super(reason);
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }
}
