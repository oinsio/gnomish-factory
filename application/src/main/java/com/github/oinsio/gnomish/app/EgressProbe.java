package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.egress.AdmittedEndpoint;

/**
 * The {@code EGRESS} condition's check (design D16 of migrate-verify-to-executor-contract, FR18; ADR
 * 0019 d.3, a condition's probe is its own check): the connect-time judgement of the tracker's
 * admitted endpoint — its host resolved, every address judged against its admitted set — with no
 * request sent. A role interface the startup owner asks, so a spec drives the probe without a
 * resolver; the composition root binds the egress guard's own judgement.
 *
 * <p>Implements FR18, NFR-R5 of migrate-verify-to-executor-contract.
 */
@FunctionalInterface
public interface EgressProbe {

    /**
     * Judges {@code endpoint} once.
     *
     * @param endpoint the tracker's admitted endpoint; never null
     * @throws com.github.oinsio.gnomish.domain.egress.EgressRefusedException when the operator's
     *     grants do not admit where the endpoint now leads
     * @throws RuntimeException when no judgement could be made (the host does not resolve)
     */
    void judge(AdmittedEndpoint endpoint);
}
