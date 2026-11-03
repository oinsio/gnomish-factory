package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.domain.engine.UsageCost;
import com.github.oinsio.gnomish.domain.engine.UsageFold;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The one spelling of a usage snapshot's cost on the operator's planes (FR20, UX4 of
 * define-executor-contract): the {@code gnomish usage --markdown} report and the {@code gnomish
 * status} text both name a cost in these words, so a lower bound reads the same wherever it is
 * shown.
 *
 * <p>A cost that is not complete is a lower bound and says so: "at least" the priced sum, then
 * "cost incomplete:" and what is missing — the tokens no price covered, or that a round reported no
 * usage at all when none is left unpriced (design D15). Formatting only: the unpriced figure and its
 * count are the one {@link UsageFold}'s.
 *
 * <p>Implements FR20, UX4 of define-executor-contract.
 */
public final class UsageCostWords {

    private UsageCostWords() {}

    /**
     * The cost of a snapshot: the priced sum, prefixed with "at least" and followed by what is
     * missing when the cost is incomplete.
     *
     * @param cost the folded cost; never null
     * @return the rendered cost, one line
     */
    public static String cost(UsageCost cost) {
        String priced = "$" + dollars(cost.priced());
        if (cost.complete()) {
            return priced;
        }
        long unpriced = UsageFold.count(UsageFold.unpricedTotal(cost));
        String missing = unpriced > 0 ? unpriced + " tokens unpriced" : "usage not reported";
        return "at least " + priced + "; cost incomplete: " + missing;
    }

    /**
     * Dollars to the cent from one dollar up, to a hundredth of a cent below, where cents say nothing.
     *
     * @param amount a sum in USD; never null
     * @return the amount without the currency sign
     */
    public static String dollars(BigDecimal amount) {
        int scale = amount.compareTo(BigDecimal.ONE) >= 0 ? 2 : 4;
        return amount.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }
}
