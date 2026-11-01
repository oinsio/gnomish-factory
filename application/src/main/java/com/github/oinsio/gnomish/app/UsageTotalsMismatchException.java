package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.engine.RoundToken;
import com.github.oinsio.gnomish.domain.engine.TokenUsage;
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot;
import java.io.Serial;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * Ends a {@code gnomish usage} run whose re-fold of the walked rounds differs from the {@code
 * totals} the tip records (FR18, design D15 of define-executor-contract): a mismatch is an error
 * naming both figures, never a silent report, so no table or document is rendered. The message is
 * the operator's line — {@link RunExceptionReporting} prints it on the error console's human path,
 * where the model ids it names (branch content) are neutralized — and {@link RunExitCodeMapper}
 * settles it on the generic exit code 1.
 *
 * <p>Each figure is spelled in full: rounds, {@code through}, the four token counts per role and
 * model in a stable order, the priced sum, the unpriced tokens and completeness, so a difference in
 * any one part shows in the two lines.
 *
 * <p>Implements FR18 of define-executor-contract.
 */
public final class UsageTotalsMismatchException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param taskId the task whose usage was walked; never blank
     * @param folded the fold of the walked rounds; never null
     * @param recorded the {@code totals} the tip records; never null
     */
    public UsageTotalsMismatchException(String taskId, UsageSnapshot folded, UsageSnapshot recorded) {
        super("usage: the recorded totals of " + taskId + " differ from the fold of its walked rounds; no report"
                + " is rendered\n  folded:   " + describe(folded) + "\n  recorded: " + describe(recorded));
    }

    /** One snapshot spelled in full, its maps in key order. */
    static String describe(UsageSnapshot snapshot) {
        RoundToken through = snapshot.through();
        return "rounds=" + snapshot.rounds()
                + " through=" + (through == null ? "none" : through.commit())
                + " tokens=" + tokens(snapshot.byRole())
                + " priced=" + snapshot.cost().priced().toPlainString()
                + " unpriced=" + tokens(snapshot.cost().unpricedTokens())
                + " complete=" + snapshot.cost().complete();
    }

    private static String tokens(Map<?, Map<String, TokenUsage>> byRole) {
        StringJoiner out = new StringJoiner("; ", "[", "]");
        new TreeMap<>(byRole)
                .forEach((role, models) -> new TreeMap<>(models)
                        .forEach((model, usage) -> out.add(role + "/" + model + " in=" + usage.input() + " out="
                                + usage.output() + " cacheCreation=" + usage.cacheCreation() + " cacheRead="
                                + usage.cacheRead())));
        return out.toString();
    }
}
