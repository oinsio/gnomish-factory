package com.github.oinsio.gnomish.adapter.agent.claude;

import com.github.oinsio.gnomish.executorcontract.Bounds;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Renders a round's model and its limit hints into the CLI flags {@link AgentCommandLine} inserts
 * after the {@code -p} token: {@code --model} always, then {@code --max-turns} and {@code
 * --max-budget-usd} when a bound is present. The tool sets and the permission mode are not here:
 * they compile from the request's policy in {@link ClaudePolicyCompiler}, their one owner.
 *
 * <p>The limits are hints (NFR-C1 of extract-agent-round-runner): the ledger enforces them after
 * the round whatever the CLI honoured. The turn hint is the tighter of the request's turn bound
 * and the legacy {@code maxTurns} setting, so neither source can widen the other; the budget hint
 * is the request's money bound, in US dollars as the CLI's flag takes it.
 *
 * <p>{@code settings} is the opaque, already-validated map the law hands the domain; an
 * unrecognized key is ignored as cheap defense in depth, and {@code roundTimeout} is never
 * rendered — it is the factory's own process bound, not a CLI flag.
 *
 * <p>Implements FR11, D7 of add-agent-executor; FR2, NFR-C1 of extract-agent-round-runner.
 */
final class AgentInvocationOptions {

    private static final String MODEL_FLAG = "--model";

    private static final String MAX_TURNS_FLAG = "--max-turns";

    /** The CLI's budget flag; its stop is the result subtype {@code error_max_budget_usd}. */
    private static final String MAX_BUDGET_FLAG = "--max-budget-usd";

    private static final String MAX_TURNS_KEY = ClaudeSettings.MAX_TURNS;

    private AgentInvocationOptions() {}

    /**
     * Renders the model and the limit hints of one round.
     *
     * @param model the pinned model id
     * @param settings the opaque settings map; may be empty, never null
     * @param bounds the request's bounds; {@link Bounds#none()} where the caller has none
     * @return the rendered flag tokens, always starting with {@code --model}
     */
    static List<String> render(String model, Map<String, Object> settings, Bounds bounds) {
        List<String> argv = new ArrayList<>();
        argv.add(MODEL_FLAG);
        argv.add(model);
        Long turns = tighter(bounds.turns(), settings.get(MAX_TURNS_KEY));
        if (turns != null) {
            argv.add(MAX_TURNS_FLAG);
            argv.add(Long.toString(turns));
        }
        BigDecimal money = bounds.moneyUsd();
        if (money != null) {
            argv.add(MAX_BUDGET_FLAG);
            argv.add(money.toPlainString());
        }
        return argv;
    }

    private static @Nullable Long tighter(@Nullable Long bound, @Nullable Object setting) {
        if (!(setting instanceof Number number)) {
            return bound;
        }
        long fromSetting = number.longValue();
        return bound == null ? fromSetting : Math.min(bound, fromSetting);
    }
}
