package com.github.oinsio.gnomish.domain.egress;

import java.io.Serial;

/**
 * The egress guard's refusal of a destination (FR17, NFR-R5, UX4, design D16 of
 * migrate-verify-to-executor-contract): what the operator's grants decide — a scheme, an address
 * class, a host no entry grants. It is a configuration fault, not an outage, so it is a {@link
 * RuntimeException} and deliberately no {@code IOException}: no transport retry, which retries I/O
 * failures only, can wrap it or retry it, and every layer classifies it by its type (a tracker call
 * as the operator precondition {@code EGRESS}, a verdict built-in as {@code failed/infrastructure}
 * naming the key).
 *
 * <p>An unresolvable host is not a refusal (it is the JDK's {@code UnknownHostException}, a
 * transport failure), and neither is an exchange outrunning a consumer's bound (the leaf's {@code
 * ExchangeLimitException}).
 *
 * <p>Implements FR17, NFR-R5, UX4 of migrate-verify-to-executor-contract.
 */
public final class EgressRefusedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient EgressRefusal refusal;

    public EgressRefusedException(EgressRefusal refusal) {
        super(refusal.describe());
        this.refusal = refusal;
    }

    public EgressRefusal refusal() {
        return refusal;
    }
}
