package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.adapter.console.StatusRenderer;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.status.json.StatusReportJsonMapper;

/**
 * The {@link StatusRenderer} implementation {@code DialogConsole} calls for the
 * {@code status} / {@code status --json} meta-commands (design D1, D7 of
 * add-manual-run): backed by a {@link StatusSnapshotHolder} (the live snapshot),
 * a {@link TaskContext} (the task's stable identity), a {@link
 * StatusTextRenderer} (the text render, task 6.4), and a {@link
 * StatusReportJsonMapper} (the JSON render, task 6.5).
 *
 * <p>Implements FR10, FR11, UX2, D7 of add-manual-run.
 */
public final class ConsoleStatusRenderer implements StatusRenderer {

    private final StatusSnapshotHolder holder;
    private final TaskContext context;
    private final StatusTextRenderer textRenderer;
    private final StatusReportJsonMapper jsonMapper;

    /**
     * @param holder the live snapshot to build reports from; never null
     * @param context the task's stable identity and decisions; never null
     * @param textRenderer the text render to delegate {@code render(false)} to;
     *     never null
     */
    public ConsoleStatusRenderer(StatusSnapshotHolder holder, TaskContext context, StatusTextRenderer textRenderer) {
        this.holder = holder;
        this.context = context;
        this.textRenderer = textRenderer;
        this.jsonMapper = new StatusReportJsonMapper();
    }

    /**
     * Renders the current status: the full text report for {@code json == false};
     * the v1 JSON contract document for {@code json == true} (task 6.5).
     *
     * <p>Implements FR10, FR11, UX2, D7 of add-manual-run.
     *
     * @param json when {@code true}, render as JSON; otherwise render as text
     * @return the rendered report, ready to print verbatim
     */
    @Override
    public String render(boolean json) {
        StatusReport report = holder.current(context);
        return json ? jsonMapper.serialize(report) : textRenderer.renderFull(report);
    }
}
