package com.github.oinsio.gnomish.adapter.check.http;

/**
 * Why one http target was refused before a connection to it was attempted (NFR-S2, UX2, design D5 of
 * add-plugin-architecture). A refusal is data rather than a bare exception because it is reported
 * twice — once to the operator as the check's {@code CannotVerify} reason, once in the logs — and
 * both readings need the same three facts: which target, which rule, and the concrete detail (the
 * blocked address class, the scheme that was written).
 *
 * <p>A plain final class rather than a record, for the reason {@link HttpCheckCondition} carries in
 * full: PIT cannot redefine a record's bytecode in an already-loaded class, so mutations of a record
 * accessor come back RUN_ERROR and drop the type out of the mutation gate (hcoles/pitest#1285).
 *
 * <p>Implements NFR-S2, UX2 of add-plugin-architecture.
 */
final class EgressRefusal {

    /** The rule that refused the target — the three refusal classes UX2 names. */
    enum Reason {

        /** The target is not {@code https}: an http check may not send a credential in the clear. */
        SCHEME("scheme"),

        /** The target resolves into an address class the factory never calls into. */
        ADDRESS_CLASS("address class"),

        /** No operator allowlist entry permits the host. */
        NOT_ALLOWLISTED("missing allowlist entry"),

        /** The host does not resolve, so its address class cannot be judged — refused fail-closed. */
        UNRESOLVABLE("unresolvable host"),

        /** The chain of redirects outran its bound; the hop after the last permitted one. */
        REDIRECT_LIMIT("redirect limit"),

        /** The response outgrew the size a verdict may be read from. */
        RESPONSE_SIZE("response size limit");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    private final Reason reason;
    private final String target;
    private final String detail;

    EgressRefusal(Reason reason, String target, String detail) {
        this.reason = reason;
        this.target = target;
        this.detail = detail;
    }

    Reason reason() {
        return reason;
    }

    String target() {
        return target;
    }

    String detail() {
        return detail;
    }

    /** The operator-facing sentence: the target, the rule that refused it, and why (UX2). */
    String describe() {
        return "http check target '%s' refused by the factory egress allowlist (%s): %s"
                .formatted(target, reason.label(), detail);
    }
}
