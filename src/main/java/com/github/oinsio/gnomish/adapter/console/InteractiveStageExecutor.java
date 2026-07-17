package com.github.oinsio.gnomish.adapter.console;

import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.ExecutionResult;
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The interactive {@link StageExecutor}: a human plays the gnome. Prints the
 * {@link StageBriefing} for the round, then prompts the operator — empty Enter
 * completes the round with the measured wall time, no token usage and an empty
 * trace (FR3); {@code ask} opens a question-and-options dialog and hands
 * control back as a gnome-initiated {@link ExecutionResult.DecisionNeeded}
 * (FR3, design D6). Any other first answer re-prompts (UX1).
 *
 * <p>Implements FR3 of add-manual-run.
 */
public final class InteractiveStageExecutor implements StageExecutor {

    private static final String COMPLETE_ANSWER = "";
    private static final String ASK_ANSWER = "ask";
    private static final String ROUND_PROMPT = "Press Enter when done, or type 'ask' to ask a question: ";
    private static final String QUESTION_PROMPT = "Question: ";
    private static final String OPTION_PROMPT = "Option (empty line to finish): ";

    private final DialogConsole console;
    private final StageBriefing briefing;

    public InteractiveStageExecutor(DialogConsole console, StageBriefing briefing) {
        this.console = console;
        this.briefing = briefing;
    }

    /**
     * Runs one interactive round: prints the briefing, then prompts until the
     * operator either finishes the round (empty Enter) or asks a question.
     *
     * <p>Implements FR3 of add-manual-run.
     *
     * @param request the round's inputs
     * @return {@link ExecutionResult.Completed} on empty Enter, or {@link
     *     ExecutionResult.DecisionNeeded} after an {@code ask} dialog
     */
    @Override
    public ExecutionResult execute(Request request) {
        console.print(briefing.render(request));
        long startNanos = System.nanoTime();
        String answer = console.ask(ROUND_PROMPT, List.of(COMPLETE_ANSWER, ASK_ANSWER));
        if (ASK_ANSWER.equals(answer)) {
            String question = console.prompt(QUESTION_PROMPT);
            List<String> options = collectOptions();
            return new ExecutionResult.DecisionNeeded(question, options, usage(startNanos), trace(request));
        }
        return new ExecutionResult.Completed(usage(startNanos), trace(request));
    }

    private List<String> collectOptions() {
        List<String> options = new ArrayList<>();
        while (true) {
            String option = console.prompt(OPTION_PROMPT);
            if (option.isEmpty()) {
                return options;
            }
            options.add(option);
        }
    }

    private ExecutorUsage usage(long startNanos) {
        Duration wallTime = Duration.ofNanos(System.nanoTime() - startNanos);
        return new ExecutorUsage(wallTime, List.of(), Map.of());
    }

    private ToolTrace trace(Request request) {
        AttemptKey key =
                new AttemptKey(request.context().taskId(), request.stage().name(), request.attempt());
        return new ToolTrace(key, List.of());
    }
}
