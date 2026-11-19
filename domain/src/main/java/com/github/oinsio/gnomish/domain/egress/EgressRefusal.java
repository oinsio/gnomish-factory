package com.github.oinsio.gnomish.domain.egress;

/**
 * Why one destination was refused before a connection to it was attempted (NFR-S2, UX2 of
 * add-plugin-architecture; FR17, UX4 of migrate-verify-to-executor-contract): which target, which
 * rule, the concrete detail, and the configuration key of the consumer whose URL was judged, so the
 * operator reads the key to edit rather than a guard's internals.
 *
 * <p>A plain final class rather than a record: PIT cannot redefine a record's bytecode in an
 * already-loaded class, so mutations of a record accessor come back RUN_ERROR and drop the type out
 * of the mutation gate (hcoles/pitest#1285).
 *
 * <p>Implements NFR-S2, UX2 of add-plugin-architecture; FR17, UX4 of
 * migrate-verify-to-executor-contract.
 */
public final class EgressRefusal {

    /**
     * The rule that refused the target — only what the operator's grants decide (design D16, the
     * outcome classes): an unknown host is a transport failure and an outrun bound an exchange
     * limit, neither a refusal.
     */
    public enum Reason {

        /** The target's scheme is not the one its medium may use, or a hop downgrades it. */
        SCHEME("scheme"),

        /** The target resolves into an address class no entry of its admitted set waives. */
        ADDRESS_CLASS("address class"),

        /** No entry of the admitted set grants the host. */
        NOT_ALLOWLISTED("missing allowlist entry");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Reason reason;
    private final String target;
    private final String detail;
    private final String key;

    /**
     * @param reason the rule; never null
     * @param target the refused URL as written; never null
     * @param detail the concrete fact (the class, the scheme, the entries); never null
     * @param key the consumer's configuration key, e.g. {@code tracker.github.api-url}; never null
     */
    public EgressRefusal(Reason reason, String target, String detail, String key) {
        this.reason = reason;
        this.target = target;
        this.detail = detail;
        this.key = key;
    }

    public Reason reason() {
        return reason;
    }

    public String target() {
        return target;
    }

    public String detail() {
        return detail;
    }

    public String key() {
        return key;
    }

    /** The operator-facing sentence: the target, the rule, why, and the key to edit (UX2, UX4). */
    public String describe() {
        return "target '%s' refused by the factory egress guard (%s, %s): %s"
                .formatted(target, reason.label(), key, detail);
    }
}
