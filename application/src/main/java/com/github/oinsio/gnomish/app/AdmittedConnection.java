package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.egress.AdmittedEndpoint;
import java.util.Map;

/**
 * A credentialed built-in's operator connection as run assembly read and admitted it (design D16,
 * D18 of migrate-verify-to-executor-contract): the endpoint the guard admitted and the resolved
 * {@code factory.check.<name>} subsection. It travels from the describe step, where a refusal is
 * decided, to the executor registry assembly with the {@link DescribedLaw}, so a run reads each
 * connection once. It holds no secret: the token is resolved where the built-in is built.
 *
 * <p>Implements FR13, FR17, NFR-S2 of migrate-verify-to-executor-contract.
 *
 * @param endpoint the admitted endpoint; never null
 * @param settings the subsection with its {@code connection:} profile resolved; never null
 */
public record AdmittedConnection(AdmittedEndpoint endpoint, Map<String, Object> settings) {

    public AdmittedConnection {
        settings = Map.copyOf(settings);
    }
}
