package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.adapter.pipeline.PathSafety;
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.domain.engine.port.BuiltinCheckRunner;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The real {@code files_exist} built-in check runner (design D2): checks that
 * every literal workspace-relative path in the {@code files} param exists on
 * disk, collecting one {@link Finding} per missing path. Malformed params or a
 * path resolving outside the workspace root never reach an existence check —
 * they yield {@link Verdict.CannotVerify} instead, since no verdict about the
 * artifact's quality can be trusted from an unsafe or unparseable input.
 *
 * <p>Implements FR6 of add-manual-run.
 */
public final class FilesExistCheckRunner implements BuiltinCheckRunner {

    @Override
    public Verdict run(VerifyCheck.Builtin check, Workspace workspace) {
        if (!(workspace instanceof DirectoryWorkspace directoryWorkspace)) {
            return opaqueWorkspaceVerdict(workspace);
        }

        List<String> files;
        try {
            files = readFiles(check.params());
        } catch (MalformedParamsException e) {
            return new Verdict.CannotVerify(e.reason(), "");
        }

        Path root = directoryWorkspace.root();
        List<Finding> findings = new ArrayList<>();
        for (String file : files) {
            PathSafety.Resolution resolution = PathSafety.resolveWithinRoot(root, file);
            if (resolution instanceof PathSafety.Escapes escapes) {
                return new Verdict.CannotVerify("files_exist path escapes the workspace: " + escapes.ref(), "");
            }
            PathSafety.Within within = (PathSafety.Within) resolution;
            if (!Files.exists(within.path())) {
                findings.add(new Finding("missing file: " + file, file, null));
            }
        }

        return findings.isEmpty() ? new Verdict.Pass() : new Verdict.Fail(findings);
    }

    /**
     * Builds the {@link Verdict.CannotVerify} for a non-{@link DirectoryWorkspace}
     * argument, naming the workspace's actual runtime type.
     *
     * <p>PIT M4 documented exception (build.gradle has the full rationale):
     * {@code @DoNotMutate} — PIT's coverage-instrumentation phase silently
     * fails to attribute coverage to a {@code new Verdict.CannotVerify(...)}
     * construction at this call shape (a record implementing a sealed
     * interface), the same JVMTI RedefineClasses/record-attribute restriction
     * as the RUN_ERROR cases elsewhere in this codebase (hcoles/pitest#1285) —
     * NO_COVERAGE, not a real gap: "a workspace that is not a DirectoryWorkspace
     * yields CannotVerify" in FilesExistCheckRunnerSpec exercises this method
     * directly and passes. Isolated to its own method so the rest of {@link
     * #run} (readFiles/escape/missing-file branches, all record constructions
     * of the very same {@link Verdict} type that PIT mutates without issue)
     * stays under the 100% mutation gate.
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
                throw new MalformedParamsException("files_exist 'files' entries must all be strings, found "
                        + (entry == null ? "null" : entry.getClass().getName()));
            }
            files.add(stringEntry);
        }
        return files;
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
