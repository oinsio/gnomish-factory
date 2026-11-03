package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.UsageRow;
import com.github.oinsio.gnomish.domain.engine.ExecutorRole;
import com.github.oinsio.gnomish.domain.engine.ParticipantUsage;
import com.github.oinsio.gnomish.domain.engine.TokenUsage;
import com.github.oinsio.gnomish.domain.engine.UsageFold;
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Renders {@code gnomish usage}'s text mode (FR14, NFR-C1 of add-git-workflow, spec "Usage
 * report"): a stage/round table — one line per {@link UsageRow} with the stage, round, result,
 * tokens summed over the round's participants and models (in/out/cache), and the executor's wall
 * time — followed by the {@link UsageSnapshot}. Every sum is the one {@link UsageFold}'s: a row's
 * tokens are the total of that round folded alone, the totals line the snapshot's (design D15 of
 * define-executor-contract); this class adds nothing itself. Full per-model/per-vote granularity is
 * the {@code --json} rendering's job ({@code usage.json.UsageReportJsonMapper}).
 *
 * <p>Implements FR14, NFR-C1 of add-git-workflow; FR17 of define-executor-contract.
 */
final class UsageTextRenderer {

    private static final String HEADER = "%-20s %-6s %-15s %10s %10s %10s %10s"
            .formatted("STAGE", "ROUND", "RESULT", "IN", "OUT", "CACHE", "WALL_MS");

    /**
     * Renders {@code rows} as a plain-text table with a totals line, or "no rounds recorded" when
     * {@code rows} is empty.
     *
     * @param rows every reconstructed round, oldest to newest; possibly empty
     * @param totals the usage snapshot of {@code rows}
     * @return the rendered text block, ready to print verbatim
     */
    String render(List<UsageRow> rows, UsageSnapshot totals) {
        if (rows.isEmpty()) {
            return "no rounds recorded";
        }
        StringBuilder out = new StringBuilder(HEADER);
        for (UsageRow row : rows) {
            out.append('\n').append(renderRow(row));
        }
        out.append('\n').append(renderTotals(totals));
        return out.toString();
    }

    private String renderRow(UsageRow row) {
        TokenUsage summed = UsageFold.total(UsageFold.foldAll(List.of(row.attempt())));
        Duration wall = executorWall(row.attempt().usage());
        return "%-20s %-6d %-15s %10d %10d %10d %10s"
                .formatted(
                        row.attempt().stage(),
                        row.attempt().round(),
                        renderResult(row),
                        summed.input(),
                        summed.output(),
                        summed.cacheCreation() + summed.cacheRead(),
                        wall == null ? "-" : wall.toMillis());
    }

    private String renderTotals(UsageSnapshot totals) {
        TokenUsage summed = UsageFold.total(totals);
        return "TOTAL: in=%d out=%d cache=%d rounds=%d priced=%s complete=%s"
                .formatted(
                        summed.input(),
                        summed.output(),
                        summed.cacheCreation() + summed.cacheRead(),
                        totals.rounds(),
                        totals.cost().priced().toPlainString(),
                        totals.cost().complete());
    }

    /** The wall time of the round's executor — its {@code transform} entry — or {@code null}. */
    private static @Nullable Duration executorWall(List<ParticipantUsage> usage) {
        for (ParticipantUsage entry : usage) {
            if (entry.role() == ExecutorRole.TRANSFORM) {
                return entry.wallTime();
            }
        }
        return null;
    }

    /**
     * The row's result as the same lowerCamel discriminator {@code state.json} carries — rendered
     * here from the domain enum rather than read off a state-file DTO, so this renderer stays free
     * of the git adapter's wire format (FR12b, design D12 of split-into-modules).
     */
    private static String renderResult(UsageRow row) {
        return switch (row.attempt().result()) {
            case PASSED -> "passed";
            case QUALITY_FAILURE -> "qualityFailure";
            case CANNOT_VERIFY -> "cannotVerify";
            case DECISION_NEEDED -> "decisionNeeded";
            case BUDGET_EXHAUSTED -> "budgetExhausted";
        };
    }
}
