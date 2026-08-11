package com.github.oinsio.gnomish.adapter.console;

import com.github.oinsio.gnomish.adapter.briefing.BriefingSections;
import com.github.oinsio.gnomish.adapter.law.PipelineLaw;
import com.github.oinsio.gnomish.adapter.law.UnreadableLawFileException;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;

/**
 * Renders the human-readable briefing block the interactive {@code StageExecutor}
 * (task 5.2) prints before prompting the operator (FR3): task goal, input
 * artifacts, prior-attempt feedback, decisions, and the stage's control-file
 * content taken from the invocation's frozen {@link PipelineLaw}. A pure
 * formatting helper — it builds text only, it never prompts or reads console
 * input.
 *
 * <p>Section formatting itself lives in the shared {@link BriefingSections}
 * (FR14, D8 of add-agent-executor); this class keeps only what is specific to
 * the interactive adapter's policy: taking the control file from the frozen law
 * (D14, FR19 of add-sandbox-core) — the gnome-unwritable law source, not the
 * working copy — and degrading any read failure to a placeholder line rather
 * than throwing, so a briefing render failure never crashes the interactive
 * dialog.
 *
 * <p>Input artifacts carry no physical file path yet (proposal open question
 * Q1), so they are rendered symbolically by kind and producer id rather than by
 * reading files.
 *
 * <p>Implements FR3 of add-manual-run; control-file reading kept here (moved
 * out of the shared renderer) per FR14, D8 of add-agent-executor; the frozen-law
 * source per FR19, D14 of add-sandbox-core.
 *
 * @param law the invocation's frozen pipeline law, the source of the
 *     control-file content this briefing renders (D14 of add-sandbox-core);
 *     never null
 */
public record StageBriefing(PipelineLaw law) {

    /**
     * Renders the full briefing for {@code request}: task goal, input
     * artifacts, prior-attempt feedback, decisions, and the stage's control-file
     * content, in that order.
     *
     * <p>Implements FR3 of add-manual-run.
     *
     * @param request the round's inputs, as passed to the interactive {@code
     *     StageExecutor}
     * @return the multi-section briefing text; never null
     */
    public String render(StageExecutor.Request request) {
        StringBuilder out = new StringBuilder();
        BriefingSections.renderExecutorBriefing(
                out,
                request.context(),
                request.stage().inputs(),
                request.feedback(),
                request.stage().instructionsRef(),
                readControlFile(request));
        return out.toString();
    }

    private String readControlFile(StageExecutor.Request request) {
        String ref = request.stage().instructionsRef();
        try {
            return law.controlFile(ref);
        } catch (UnreadableLawFileException e) {
            return "(control file could not be read: " + ref + ")";
        }
    }
}
