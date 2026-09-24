package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The <em>run order</em>: the mode-independent description of the work one invocation performs —
 * where the clone is, which base to start from, which pipeline, which console mode, and whether to
 * discard an interrupted round's leftovers. Both manual {@code gnomish run} and tracker-driven
 * {@code take}/{@code serve} carry it; the tracker-driven modes wrap it in a {@link TakeOrder}
 * (design D1 of introduce-take-order).
 *
 * <p>An order is the resolved instruction to a runner, not a parsed command line: each entry point
 * builds one from its own parsed record ({@link RunArguments}, {@link TakeArguments}, the serve
 * arguments) plus the {@link PipelineDefinition} loaded from {@code .gnomish/} after parsing. Those
 * entry points are the only places a parsed record becomes an order.
 *
 * <p>Implements FR1 of introduce-take-order.
 *
 * @param cloneDir the clone the invocation works in
 * @param base the {@code --base} override, or {@code null} to let base resolution decide
 * @param definition the pipeline the invocation runs under, as loaded at startup
 * @param interactiveMode which role(s), if any, the console adapters replace
 * @param discardWork whether to reset to the last recorded round instead of salvaging an
 *     interrupted round's uncommitted work
 */
public record RunOrder(
        Path cloneDir,
        @Nullable String base,
        PipelineDefinition definition,
        RunArguments.InteractiveMode interactiveMode,
        boolean discardWork) {

    /**
     * This order re-bound to {@code taskDefinition}, every other field unchanged — reached only
     * through {@link TakeOrder#withDefinition} (design D6 of introduce-take-order).
     */
    RunOrder withDefinition(PipelineDefinition taskDefinition) {
        return new RunOrder(cloneDir, base, taskDefinition, interactiveMode, discardWork);
    }
}
