package com.github.oinsio.gnomish.adapter.check.http;

import java.io.IOException;

/**
 * Raised instead of performing an exchange the {@link EgressAllowlist} refuses (NFR-S2, UX2 of
 * add-plugin-architecture). It extends {@link IOException} deliberately: a refusal is the network
 * seam declining to reach a target, so every caller that already handles "the exchange could not be
 * completed" keeps working — {@link HttpExternalCheckClient} classifies it as {@code CannotVerify},
 * an infrastructure failure that burns no stage attempt, since a blocked target says nothing about
 * the artifact under verification.
 *
 * <p>It carries the {@link EgressRefusal} rather than only a message so the client can report the
 * refusal's own reason as the check's reason (UX2).
 *
 * <p>Implements NFR-S2, UX2 of add-plugin-architecture.
 */
final class EgressRefusedException extends IOException {

    private static final long serialVersionUID = 1L;

    private final transient EgressRefusal refusal;

    EgressRefusedException(EgressRefusal refusal) {
        super(refusal.describe());
        this.refusal = refusal;
    }

    EgressRefusal refusal() {
        return refusal;
    }
}
