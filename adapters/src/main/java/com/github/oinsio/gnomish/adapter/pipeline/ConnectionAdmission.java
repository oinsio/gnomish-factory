package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.app.ConnectionProfiles;
import com.github.oinsio.gnomish.domain.egress.AdmittedEndpoint;
import com.github.oinsio.gnomish.domain.egress.ConnectionEndpoint;
import com.github.oinsio.gnomish.domain.egress.EgressMedium;
import com.github.oinsio.gnomish.domain.egress.EgressRefusedException;
import com.github.oinsio.gnomish.domain.egress.EndpointDeclaration;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.httpegress.EgressAdmission;
import com.github.oinsio.gnomish.httpegress.OperatorEgress;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the loader needs to admit a tracker's endpoint at load (FR15, FR17, NFR-R5, UX4, design D16
 * of migrate-verify-to-executor-contract): the operator's named connection profiles, the endpoint
 * each discovered adapter declares (keyed by {@code tracker.type}, as the subsection validators
 * are), and the operator's {@code in-process} egress scope. The profiles live in operator
 * configuration, the declarations in the adapters, the subsection in the target repository — only
 * the composition root sees all three, so they travel down the load together.
 *
 * <p>{@link #trackerEndpoint} is the load-time judgement of the tracker's URL: the subsection's own
 * value is the law's, a referenced profile's the operator's ({@link ConnectionProfiles#endpoint}),
 * and {@link EgressAdmission} decides. A refusal is a located load error at the endpoint's key.
 *
 * <p>Implements FR15, FR17, NFR-R5, UX4 of migrate-verify-to-executor-contract.
 *
 * @param profiles the operator-declared {@code factory.connections} profiles; never null
 * @param endpoints each discovered tracker adapter's endpoint declaration, by type; never null
 * @param operator the operator's {@code factory.egress.in-process} scope; never null
 */
public record ConnectionAdmission(
        ConnectionProfiles profiles, Map<String, EndpointDeclaration> endpoints, OperatorEgress operator) {

    private static final String CONFIG_FILE = "config.yaml";

    public ConnectionAdmission {
        endpoints = Map.copyOf(endpoints);
    }

    /**
     * The profiles alone: no adapter declares an endpoint and the operator grants nothing, so a
     * mapped tracker carries no endpoint — a load that only reads the definition.
     */
    public static ConnectionAdmission of(ConnectionProfiles profiles) {
        return new ConnectionAdmission(profiles, Map.of(), OperatorEgress.none());
    }

    /**
     * Admits the endpoint of the tracker {@code type} at load.
     *
     * @param type the mapped {@code tracker.type}; never null
     * @param subsection the raw {@code tracker.<type>} subsection, before profile resolution; never
     *     null
     * @param errors where a refusal or a malformed URL is reported, located at the endpoint's key
     * @return the admitted endpoint, or empty when the adapter declares none, the subsection names
     *     none (its validator reports that), or the admission refused it
     */
    Optional<AdmittedEndpoint> trackerEndpoint(String type, Map<String, Object> subsection, List<ConfigError> errors) {
        EndpointDeclaration declaration = endpoints.get(type);
        if (declaration == null) {
            return Optional.empty();
        }
        EndpointDeclaration placed = declaration.under("tracker." + type);
        try {
            Optional<ConnectionEndpoint> endpoint = profiles.endpoint(subsection, declaration.key(), EgressMedium.LAW);
            return endpoint.map(read -> EgressAdmission.admit(read, placed, operator));
        } catch (EgressRefusedException refused) {
            errors.add(
                    new ConfigError(CONFIG_FILE, placed.key(), refused.refusal().describe()));
        } catch (IllegalArgumentException malformed) {
            errors.add(new ConfigError(CONFIG_FILE, placed.key(), "not a URL: " + malformed.getMessage()));
        }
        return Optional.empty();
    }
}
