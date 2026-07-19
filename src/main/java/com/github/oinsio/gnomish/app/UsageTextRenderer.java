package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.git.UsageRow;
import com.github.oinsio.gnomish.adapter.git.UsageTotals;
import com.github.oinsio.gnomish.adapter.git.state.StateTokenUsageDto;
import com.github.oinsio.gnomish.adapter.git.state.StateUsageDto;
import java.util.List;
import java.util.Map;

/**
 * Renders {@code gnomish usage}'s text mode (FR14, NFR-C1 of add-git-workflow, spec "Usage
 * report"): a stage/round table — one line per {@link UsageRow} with the stage, round, result,
 * tokens summed across models (in/out/cache), and wall time — plus a totals line from {@link
 * UsageTotals}. Full per-model/per-vote granularity is the {@code --json} rendering's job ({@code
 * usage.json.UsageReportJsonMapper}); this class only ever sums.
 *
 * <p>Implements FR14, NFR-C1 of add-git-workflow.
 */
final class UsageTextRenderer {

    private static final String HEADER = "%-20s %-6s %-15s %10s %10s %10s %10s"
            .formatted("STAGE", "ROUND", "RESULT", "IN", "OUT", "CACHE", "WALL_MS");

    /**
     * Renders {@code rows} as a plain-text table with a totals line, or "no rounds recorded" when
     * {@code rows} is empty.
     *
     * @param rows every reconstructed round, oldest to newest; possibly empty
     * @param totals the cumulative usage across {@code rows}
     * @return the rendered text block, ready to print verbatim
     */
    String render(List<UsageRow> rows, UsageTotals totals) {
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
        StateUsageDto usage = row.executorUsage();
        long[] summed = sumTokens(usage.tokensByModel());
        return "%-20s %-6d %-15s %10d %10d %10d %10s"
                .formatted(
                        row.stage(),
                        row.attempt().round(),
                        row.attempt().result(),
                        summed[0],
                        summed[1],
                        summed[2] + summed[3],
                        usage.wallMillis() == null ? "-" : usage.wallMillis());
    }

    private String renderTotals(UsageTotals totals) {
        long[] summed = sumTokens(totals.tokensByModel());
        return "TOTAL: in=%d out=%d cache=%d wallMillis=%s"
                .formatted(
                        summed[0],
                        summed[1],
                        summed[2] + summed[3],
                        totals.wallMillis() == null ? "unknown" : totals.wallMillis());
    }

    /** {@code [input, output, cacheCreation, cacheRead]} summed across every model in the map. */
    private static long[] sumTokens(Map<String, StateTokenUsageDto> tokensByModel) {
        long[] sum = new long[4];
        for (StateTokenUsageDto tokens : tokensByModel.values()) {
            sum[0] += tokens.input();
            sum[1] += tokens.output();
            sum[2] += tokens.cacheCreation();
            sum[3] += tokens.cacheRead();
        }
        return sum;
    }
}
