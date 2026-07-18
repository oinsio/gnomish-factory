package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.adapter.pipeline.PathSafety;
import java.io.IOException;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The CLI-executor-side half of D8's per-adapter control-file policy (FR13):
 * where the interactive adapter degrades an unreadable control file to a
 * placeholder string the human sees and judges for themselves ({@code
 * StageBriefing#readControlFile}), the agent gets a stop instead — a
 * silently control-less prompt could still pass verify with nobody noticing.
 * {@link #read} either returns the file's content unchanged, or throws {@link
 * UnreadableControlFileException} before the caller ever spawns the agent
 * process ("cannot execute", FR13).
 *
 * <p>Reuses the same {@link PathSafety#resolveWithinRoot} traversal guard the
 * interactive adapter uses, so both adapters agree on what "escapes the
 * workspace" means; only the failure reaction differs, per D8.
 *
 * <p>This is a narrow, reusable preflight primitive: task 6.1 (the eventual
 * {@code CliStageExecutor}) is expected to call this before {@link
 * AgentProcessLauncher#launch}, and task 7.3 mirrors the same read for the
 * judge's acceptance-criteria file — both let {@link
 * UnreadableControlFileException} propagate or translate it into their own
 * infrastructure-failure outcome ({@code CannotExecute} / {@code
 * CannotVerify}).
 *
 * <p>Implements FR13, D8 of add-agent-executor.
 */
public final class ControlFilePreflight {

    private ControlFilePreflight() {}

    /**
     * Reads the control file at {@code ref}, resolved against {@code root}.
     *
     * <p>Implements FR13, D8 of add-agent-executor.
     *
     * @param root the workspace root the control file is resolved against
     * @param ref the control-file reference from the stage's {@code
     *     instructionsRef}
     * @return the file's content, exactly as stored; never null
     * @throws UnreadableControlFileException if {@code ref} escapes {@code
     *     root}, or the file cannot be read (missing, permission denied, or
     *     any other I/O failure)
     */
    public static String read(Path root, String ref) {
        PathSafety.Resolution resolution = PathSafety.resolveWithinRoot(root, ref);
        if (resolution instanceof PathSafety.Escapes escapes) {
            throw new UnreadableControlFileException(ref, "path escapes the workspace: " + escapes.ref());
        }
        PathSafety.Within within = (PathSafety.Within) resolution;
        try {
            return Files.readString(within.path());
        } catch (IOException e) {
            String reason =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new UnreadableControlFileException(ref, reason);
        }
    }

    /**
     * Thrown when the control file cannot be read before an agent process
     * would otherwise spawn: an infrastructure failure of the round, no
     * attempt burned (FR13, NFR-R1, D8). Unchecked, following this
     * codebase's established idiom for infrastructure-failure signaling (see
     * {@link MissingResultEventException}): callers are expected to let it
     * propagate uncaught and shape it into their own port-level
     * infrastructure-failure outcome.
     */
    public static final class UnreadableControlFileException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * @param ref the control-file reference exactly as declared in the
         *     stage manifest
         * @param reason a short, human-readable cause, folded into the
         *     exception message for diagnosability
         */
        public UnreadableControlFileException(String ref, String reason) {
            super("control file could not be read: " + ref + " (" + reason + ")");
        }
    }
}
