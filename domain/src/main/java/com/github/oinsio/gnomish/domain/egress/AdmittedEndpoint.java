package com.github.oinsio.gnomish.domain.egress;

import java.net.URI;
import java.util.List;

/**
 * An endpoint after egress admission (FR15, FR17, design D16 of
 * migrate-verify-to-executor-contract): its URL, the configuration key a refusal of its calls names,
 * and its admitted set — the entries, in the one egress grammar, every hop of its calls is judged
 * against at connect: the connection's host when a name grants it, the connection's operator {@code
 * egress}, and the operator's {@code in-process} scope.
 *
 * <p>Minted only by {@code EgressAdmission.admit} in {@code :httpegress} ({@code
 * EgressMediumMintingSpec}), so a consumer holding one holds a URL the guard has judged; the GitHub
 * core takes this type and no {@code String} URL.
 *
 * <p>Implements FR15, FR17 of migrate-verify-to-executor-contract.
 *
 * @param url the endpoint; never null
 * @param key the configuration key the endpoint was read from, named by every refusal; never blank
 * @param admitted the admitted set, as entries of the egress grammar; never null
 */
public record AdmittedEndpoint(URI url, String key, List<String> admitted) {

    public AdmittedEndpoint {
        admitted = List.copyOf(admitted);
    }
}
