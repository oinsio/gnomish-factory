package com.github.oinsio.gnomish.domain.egress;

import java.net.URI;
import java.util.List;

/**
 * A connection's endpoint as configuration names it, with the medium it was read from (FR17,
 * design D16 of migrate-verify-to-executor-contract): minted only where the configuration is read
 * ({@code ConnectionProfiles.endpoint}), so the medium travels with the URL instead of being
 * guessed downstream from a merged map.
 *
 * <p>{@code egress} is the connection's own {@code egress} list when the operator medium carries it
 * — a referenced {@code factory.connections.<name>} profile, or an inline operator subsection — and
 * empty otherwise: a list the law tree writes beside its own URL grants nothing.
 *
 * <p>Implements FR17 of migrate-verify-to-executor-contract.
 *
 * @param url the endpoint as written; never null
 * @param medium where the URL was written; never null
 * @param egress the operator-medium grants of this connection, as written; never null
 */
public record ConnectionEndpoint(URI url, EgressMedium medium, List<String> egress) {

    public ConnectionEndpoint {
        egress = List.copyOf(egress);
    }
}
