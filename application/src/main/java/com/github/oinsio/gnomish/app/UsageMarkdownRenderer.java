package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.UsageRow;
import com.github.oinsio.gnomish.domain.engine.AttemptRecord;
import com.github.oinsio.gnomish.domain.engine.CheckResult;
import com.github.oinsio.gnomish.domain.engine.ExecutorRole;
import com.github.oinsio.gnomish.domain.engine.ParticipantUsage;
import com.github.oinsio.gnomish.domain.engine.UsageFold;
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot;
import com.github.oinsio.gnomish.status.UsageCostWords;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Renders {@code gnomish usage --markdown} (FR20, design D16 of define-executor-contract): the
 * report the usage-report stage posts on the task's pull request verbatim. It carries the time
 * elapsed since the first round, a per-stage table (rounds, agent time, checks time, tokens,
 * cost), a per-role table and a per-model table, the cost printed as a lower bound whenever the
 * snapshot says it is incomplete.
 *
 * <p>Every token and cost figure comes from the one {@link UsageFold}: a stage's figures are the
 * fold of that stage's rounds, the role and model tables and the total row read the snapshot the
 * command already checked against the tip. This class adds no token and no cost; the times it adds
 * are the agent's wall times and the check durations, which the snapshot does not carry. The roles
 * are shown by their record names with the render labels beside them ("gnome", "judge",
 * "arbiter"), which are never record vocabulary.
 *
 * <p>Implements FR20, G5 of define-executor-contract.
 */
final class UsageMarkdownRenderer {

    /** The report's heading, which the usage-report stage's check looks for on the pull request. */
    static final String HEADING = "## Factory usage";

    private static final String KIND_COLUMNS = " | Input | Output | Cache write | Cache read | Total |";
    private static final String KIND_RULE = "|------:|-------:|------------:|-----------:|------:|";

    /**
     * Renders {@code rows} as the Markdown report.
     *
     * @param rows every reconstructed round, oldest to newest; possibly empty
     * @param totals the usage snapshot of {@code rows}
     * @param now the instant the elapsed time runs to
     * @return the report, ready to post verbatim
     */
    String render(List<UsageRow> rows, UsageSnapshot totals, Instant now) {
        List<String> out = new ArrayList<>(List.of(HEADING, ""));
        if (rows.isEmpty()) {
            out.add("No rounds are recorded on the task branch.");
            return String.join("\n", out);
        }
        List<AttemptRecord> rounds = rows.stream().map(UsageRow::attempt).toList();
        Map<String, List<AttemptRecord>> byStage = byStage(rounds);
        out.add("Elapsed from the first round: "
                + UsageMarkdownCells.duration(elapsed(rounds, now))
                + ", time parked for a human included; agent time "
                + UsageMarkdownCells.duration(agentTime(rounds))
                + ", checks time "
                + UsageMarkdownCells.duration(checksTime(rounds))
                + "; cost "
                + UsageCostWords.cost(totals.cost())
                + ".");
        out.addAll(List.of("", "### Time, tokens and cost per stage", ""));
        out.add("| Stage | Rounds | Agent time | Checks time | Tokens | Cost |");
        out.add("|-------|-------:|-----------:|------------:|-------:|-----:|");
        byStage.forEach((stage, stageRounds) -> out.add(stageLine(stage, stageRounds)));
        out.add("| **Total** | " + totals.rounds() + " | " + UsageMarkdownCells.duration(agentTime(rounds)) + " | "
                + UsageMarkdownCells.duration(checksTime(rounds)) + " | "
                + UsageMarkdownCells.count(UsageFold.total(totals)) + " | "
                + UsageCostWords.cost(totals.cost()) + " |");
        out.addAll(List.of("", "### Tokens per role", ""));
        out.add("| Role" + KIND_COLUMNS);
        out.add("|------" + KIND_RULE);
        for (ExecutorRole role : ExecutorRole.values()) {
            if (totals.byRole().containsKey(role)) {
                out.add("| " + roleLabel(role) + " | " + UsageMarkdownCells.kinds(UsageFold.roleTotal(totals, role))
                        + " |");
            }
        }
        out.addAll(List.of("", "### Tokens per model", ""));
        out.add("| Model" + KIND_COLUMNS);
        out.add("|-------" + KIND_RULE);
        new TreeMap<>(UsageFold.byModel(totals))
                .forEach((model, tokens) -> out.add(
                        "| " + UsageMarkdownCells.name(model) + " | " + UsageMarkdownCells.kinds(tokens) + " |"));
        out.addAll(List.of(
                "",
                "Source: " + totals.rounds()
                        + " rounds recorded on the task branch, folded once and checked against its recorded totals."));
        return String.join("\n", out);
    }

    private static String stageLine(String stage, List<AttemptRecord> stageRounds) {
        UsageSnapshot folded = UsageFold.foldAll(stageRounds);
        return "| " + UsageMarkdownCells.name(stage) + " | " + folded.rounds() + " | "
                + UsageMarkdownCells.duration(agentTime(stageRounds)) + " | "
                + UsageMarkdownCells.duration(checksTime(stageRounds)) + " | "
                + UsageMarkdownCells.count(UsageFold.total(folded)) + " | "
                + UsageCostWords.cost(folded.cost()) + " |";
    }

    /** The rounds grouped by the stage their record names, stages in the order they first ran. */
    private static Map<String, List<AttemptRecord>> byStage(List<AttemptRecord> rounds) {
        Map<String, List<AttemptRecord>> byStage = new LinkedHashMap<>();
        for (AttemptRecord round : rounds) {
            byStage.computeIfAbsent(round.stage(), ignored -> new ArrayList<>()).add(round);
        }
        return byStage;
    }

    /** From the first round's start to {@code now}; zero when a clock ahead of this one wrote it. */
    private static Duration elapsed(List<AttemptRecord> rounds, Instant now) {
        Duration elapsed = Duration.between(rounds.getFirst().startedAt(), now);
        return elapsed.isNegative() ? Duration.ZERO : elapsed;
    }

    /** The executors' wall times: every {@code transform} entry that knows its own. */
    private static Duration agentTime(List<AttemptRecord> rounds) {
        Duration time = Duration.ZERO;
        for (AttemptRecord round : rounds) {
            for (ParticipantUsage entry : round.usage()) {
                Duration wall = entry.wallTime();
                if (entry.role() == ExecutorRole.TRANSFORM && wall != null) {
                    time = time.plus(wall);
                }
            }
        }
        return time;
    }

    /** The verification checks' durations, judges included. */
    private static Duration checksTime(List<AttemptRecord> rounds) {
        Duration time = Duration.ZERO;
        for (AttemptRecord round : rounds) {
            for (CheckResult check : round.checkResults()) {
                time = time.plus(check.duration());
            }
        }
        return time;
    }

    /** The role's record name with its render label. */
    private static String roleLabel(ExecutorRole role) {
        return switch (role) {
            case TRANSFORM -> "transform (gnome)";
            case VERDICT -> "verdict (judge)";
            case DECISION -> "decision (arbiter)";
            case EFFECT -> "effect";
        };
    }
}
