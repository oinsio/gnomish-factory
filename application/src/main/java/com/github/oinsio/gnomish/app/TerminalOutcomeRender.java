package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.status.ReportPlane;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The one render of a task that stopped and of how to continue it (design D3 of
 * make-run-headless): the escalation report, the manual-checkpoint sentence, and the return-path
 * line naming the {@code gnomish run --resume} command. {@code run}'s in-process loop and both
 * resume continuations print the stop renders; {@code take}'s pause park and its outcome mapper
 * embed {@link #checkpointLine} in their tracker reports, and every {@code take} escalation park
 * renders through {@link #renderEscalation}. Before this class the checkpoint sentence was spelled
 * in five production files; {@code TerminalRenderOwnerSpec} keeps it, and the return-path prefix,
 * in this one.
 *
 * <p>Implements FR1, FR2, FR8 of make-run-headless.
 */
public final class TerminalOutcomeRender {

    /** The head of every return-path line; a gate keeps it spelled here alone. */
    static final String RETURN_PATH_PREFIX = "To continue: gnomish run --dir=";

    /** A value that reads the same to a POSIX shell unquoted. */
    private static final Pattern SHELL_SAFE = Pattern.compile("[A-Za-z0-9_./:@%+=,-]+");

    private TerminalOutcomeRender() {}

    /**
     * The stop render of an {@code Escalated} outcome: the report on the console plane, then — when
     * the task can be resumed — the return-path line with {@code --decision} as an optional suffix.
     *
     * <p>Implements FR1, FR8 of make-run-headless.
     *
     * @param report the escalation to render; never null
     * @param returnPath where the task resumes from, or {@code null} in in-place mode, which has no
     *     branch to resume from
     * @return the rendered block; never blank
     */
    public static String escalated(EscalationReport report, @Nullable ReturnPath returnPath) {
        String rendered = renderEscalation(report, ReportPlane.CONSOLE);
        return returnPath == null ? rendered : rendered + "\n" + returnPath.line(true);
    }

    /**
     * The stop render of a {@code Paused} outcome: the checkpoint sentence, then — when the task can
     * be resumed — the return-path line.
     *
     * <p>Implements FR2, FR8 of make-run-headless.
     *
     * @param passedStage the stage whose pass reached the checkpoint; never null
     * @param returnPath where the task resumes from, or {@code null} in in-place mode
     * @return the rendered block; never blank
     */
    public static String paused(String passedStage, @Nullable ReturnPath returnPath) {
        String checkpoint = checkpointLine(passedStage);
        return returnPath == null ? checkpoint : checkpoint + "\n" + returnPath.line(false);
    }

    /**
     * The manual-checkpoint sentence, alone — what {@code take}'s park reports carry, beside a
     * tracker return path of their own.
     *
     * <p>Implements FR8 of make-run-headless.
     *
     * @param passedStage the stage whose pass reached the checkpoint; never null
     * @return {@code Stage '<stage>' passed. Manual checkpoint reached.}
     */
    public static String checkpointLine(String passedStage) {
        return "Stage '" + passedStage + "' passed. Manual checkpoint reached.";
    }

    /**
     * Renders every {@link EscalationReport} variant as a distinct, kind-specific English text block
     * by an exhaustive switch — no {@code default} arm — so a new variant fails to compile here until
     * its render is added (FR9 of add-manual-run). Every field it renders is untrusted text — a
     * check's label from the target repository's manifest, a judge's message, a command's output
     * tail, a stage name another instance recorded, an executor's failure cause — so each leaves its
     * carrier through the {@code plane} the caller is bound for (FR15 of add-sandbox-core; design D6,
     * D7 of type-untrusted-text). The attempt limit is the one component that is a number.
     *
     * <p>The plane is the caller's to choose: the console render goes to the operator's terminal,
     * where the comment plane's zero-width mention breaks and tilde fences are noise; the {@code
     * take} parks publish to the tracker, where those same devices are the neutralization. An arm
     * whose report is the factory's heading over <em>one</em> capture ({@code PipelineMismatch},
     * {@code CannotExecute}) takes {@link ReportPlane#block}, since that block really is machine
     * output; an arm that interleaves prose with several captures ({@code DecisionNeeded}, {@code
     * CannotVerify}) takes {@link ReportPlane#render} field by field (design D6 of
     * type-untrusted-text, revised 2026-09-19).
     *
     * <p>The only render of an escalation anywhere — never the record's own {@code toString}, which
     * would put {@code CannotVerify[check=..., details=...]} in front of a human, unfenced (FR7,
     * design D8 of harden-untrusted-text-sinks). Moved here from the retired resume dialog (design D3
     * of make-run-headless).
     *
     * <p>Implements FR9, D8 of add-manual-run; FR15 of add-sandbox-core; D6 of type-untrusted-text.
     *
     * @param report the escalation reason to render; never null
     * @param plane the human plane this render is bound for; never null
     * @return the rendered text block; never null, never blank
     */
    public static String renderEscalation(EscalationReport report, ReportPlane plane) {
        return switch (report) {
            case EscalationReport.AttemptsExhausted attemptsExhausted ->
                "Attempt limit (" + attemptsExhausted.limit() + ") reached — every attempt failed quality.";
            case EscalationReport.DecisionNeeded decisionNeeded ->
                "The gnome asked:\n" + plane.render(decisionNeeded.question()) + "\nOptions:\n"
                        + decisionNeeded.options().stream().map(plane::render).collect(Collectors.joining("\n"));
            case EscalationReport.CannotVerify cannotVerify ->
                "Could not verify a check named:\n"
                        + plane.render(cannotVerify.check().label()) + "\nReason:\n"
                        + plane.render(cannotVerify.reason()) + "\nDetails:\n"
                        + plane.render(cannotVerify.details());
            case EscalationReport.PipelineMismatch pipelineMismatch ->
                "A stage this task recorded is no longer defined in the pipeline:\n"
                        + plane.block(pipelineMismatch.staleStage());
            case EscalationReport.CannotExecute cannotExecute ->
                "Executor infrastructure failure:\n" + plane.block(cannotExecute.cause());
        };
    }

    /**
     * Where a stopped {@code run} task continues from: the {@code --dir} clone and the task id the
     * return-path line names.
     *
     * <p>Implements FR1, FR2, NFR-O1 of make-run-headless.
     *
     * @param dir the {@code --dir} clone the resume runs in; never null
     * @param taskId the task to resume; never blank
     */
    public record ReturnPath(Path dir, String taskId) {

        /**
         * The return-path line: {@code To continue: gnomish run --dir=<dir> --resume=<task>}, with
         * {@code [--decision="..."]} appended when {@code decisionOptional}. Every value is joined to
         * its flag by {@code =}: the CLI's option parser reads {@code --dir <dir>} as a {@code --dir}
         * without a value and refuses it (task 4.5 of make-run-headless). A value a shell would
         * split or expand is single-quoted after the {@code =} ({@code --dir='/a b'}), which a POSIX
         * shell still reads as one argument, so the line pastes as printed. That covers the task id
         * too: on a resume it is read back from the branch's {@code task.json}, where no {@code
         * --task-id} format check ever applied.
         *
         * @param decisionOptional whether the stop accepts an operator decision (an escalation)
         * @return the line, without a trailing line break
         */
        public String line(boolean decisionOptional) {
            String command = RETURN_PATH_PREFIX + shellWord(dir.toString()) + " --resume=" + shellWord(taskId);
            return decisionOptional ? command + " [--decision=\"...\"]" : command;
        }

        private static String shellWord(String word) {
            if (SHELL_SAFE.matcher(word).matches()) {
                return word;
            }
            return "'" + word.replace("'", "'\\''") + "'";
        }
    }
}
