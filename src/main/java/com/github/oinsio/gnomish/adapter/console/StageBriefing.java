package com.github.oinsio.gnomish.adapter.console;

import com.github.oinsio.gnomish.adapter.briefing.BriefingSections;
import com.github.oinsio.gnomish.adapter.pipeline.PathSafety;
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Renders the human-readable briefing block the interactive {@code StageExecutor}
 * (task 5.2) prints before prompting the operator (FR3): task goal, input
 * artifacts, prior-attempt feedback, decisions, and the stage's control-file
 * content read from disk via the workspace root. A pure formatting helper — it
 * builds text only, it never prompts or reads console input.
 *
 * <p>Section formatting itself lives in the shared {@link BriefingSections}
 * (FR14, D8 of add-agent-executor); this class keeps only what is specific to
 * the interactive adapter's policy: <em>reading</em> the control file from
 * {@code workspace.root()} when the workspace is a {@link DirectoryWorkspace},
 * and degrading any read failure (missing file, unreadable, path escape, or a
 * non-directory workspace) to a placeholder line rather than throwing, so a
 * briefing render failure never crashes the interactive dialog. Rendering
 * behavior is unchanged by the extraction — byte-identical output before and
 * after (D8: "Interactive rendering behavior does not change by a character").
 *
 * <p>Input artifacts carry no physical file path yet (proposal open question
 * Q1), so they are rendered symbolically by kind and producer id rather than by
 * reading files.
 *
 * <p>Implements FR3 of add-manual-run; control-file reading kept here (moved
 * out of the shared renderer) per FR14, D8 of add-agent-executor.
 */
public final class StageBriefing {

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
        if (!(request.workspace() instanceof DirectoryWorkspace directoryWorkspace)) {
            return "(control file unavailable: workspace is not a DirectoryWorkspace)";
        }
        String ref = request.stage().instructionsRef();
        PathSafety.Resolution resolution = PathSafety.resolveWithinRoot(directoryWorkspace.root(), ref);
        if (resolution instanceof PathSafety.Escapes escapes) {
            return "(control file could not be read: path escapes the workspace: " + escapes.ref() + ")";
        }
        PathSafety.Within within = (PathSafety.Within) resolution;
        try {
            return Files.readString(within.path());
        } catch (IOException e) {
            return "(control file could not be read: " + ref + ")";
        }
    }
}
