package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.plugin.ProviderDiscoveryReport;
import com.github.oinsio.gnomish.app.CheckClientFactory;
import com.github.oinsio.gnomish.app.CheckParamsValidator;
import com.github.oinsio.gnomish.app.ConnectionProfiles;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Supplies the composition-root check-provider registry keyed by {@code provider} — the check
 * port's counterpart of {@code TrackerAdapterConfiguration} (FR5, design D1/D3 of
 * add-plugin-architecture). Before this existed the check port had no registry at all: one vendor
 * factory was a Spring bean the run assembly named directly. Now a provider arrives as a jar with a
 * {@code META-INF/services} entry and no edit here (M1).
 *
 * <p>The bean also closes the operator-configuration seam: every configured {@code
 * factory.check.<provider>} subsection is graded by that provider's own {@link
 * com.github.oinsio.gnomish.app.CheckSubsectionValidator} and every located problem is reported
 * together, so a malformed subsection fails startup with a full report rather than surfacing as a
 * provider error mid-take (FR4, design D12).
 *
 * <p>Lives in {@code :bootstrap} but in the {@code adapter.check} package for the same by-role
 * reason as its tracker twin: building a port's registry is composition, and only the port-shaped
 * {@code Map<String, CheckClientFactory>} return type crosses back into {@code app}. It reaches the
 * context as an {@code @AutoConfiguration} listed in {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}, not by
 * component scan, which scans only {@code com.github.oinsio.gnomish.app}.
 *
 * <p>Implements FR4, FR5, M1, NFR-R1 of add-plugin-architecture.
 */
@AutoConfiguration
public class CheckClientConfiguration {

    /**
     * The file name located check-subsection errors are reported against — operator configuration,
     * not the repo-side {@code config.yaml} the pipeline loader grades.
     */
    static final String OPERATOR_CONFIG_FILE = "application.yaml";

    /** The port name the startup discovery report is written under (NFR-O1). */
    private static final String PORT = "check";

    /**
     * The discovered check providers, validated against the operator's configured subsections.
     *
     * @param factoryProperties the bound {@code factory.*} configuration, for its {@code check}
     *     subsections; never null
     * @return the providers keyed by discriminator; never null
     * @throws IllegalStateException if a subsection names an undiscovered provider or a provider's
     *     own validator rejects its content — with every located problem in the message
     */
    @Bean
    public Map<String, CheckClientFactory> checkClientRegistry(FactoryProperties factoryProperties) {
        var registry = ProviderDiscoveryReport.reported(PORT, CheckClientDiscovery.discover());
        requireValidSubsections(
                factoryProperties.check(), registry, ConnectionProfiles.of(factoryProperties.connections()));
        return registry;
    }

    /**
     * The load-time params validators {@link
     * com.github.oinsio.gnomish.adapter.pipeline.PipelineLoader} delegates each {@code external}
     * check's provider-owned {@code params} to, keyed by {@code provider} (FR6, FR13, design D3).
     *
     * <p>Derived from {@link #checkClientRegistry} rather than discovered separately (design D1,
     * D3), so the two registries are keyed identically by construction and cannot drift. Every
     * discovered provider gets an entry — one that grades no params contributes {@link
     * CheckParamsValidator#none()} — because the loader reads this registry's key set as the
     * discovered provider set: a missing key must mean "no jar serves this provider" and nothing
     * else, which is what makes an unknown {@code provider:} a located load error naming the
     * discovered set (UX1).
     *
     * @param checkClientRegistry the discovered check providers keyed by discriminator; never null
     * @return the params validators keyed by every discovered provider; never null
     */
    @Bean
    public Map<String, CheckParamsValidator> checkParamsValidatorRegistry(
            Map<String, CheckClientFactory> checkClientRegistry) {
        var validators = new LinkedHashMap<String, CheckParamsValidator>();
        checkClientRegistry.forEach((provider, factory) ->
                validators.put(provider, factory.paramsValidator().orElseGet(CheckParamsValidator::none)));
        return Map.copyOf(validators);
    }

    /**
     * Fails startup on any located subsection problem, listing them all (NFR-R1). Aggregating
     * rather than throwing at the first is the point of the validators returning {@link ConfigError}
     * data: an operator fixes every provider in one pass.
     */
    static void requireValidSubsections(
            Map<String, Map<String, Object>> configured,
            Map<String, CheckClientFactory> registry,
            ConnectionProfiles profiles) {
        List<ConfigError> errors = CheckProviderSeam.validate(OPERATOR_CONFIG_FILE, configured, registry, profiles);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("invalid check provider configuration:"
                    + errors.stream()
                            .map(e -> System.lineSeparator() + "  " + e.render())
                            .collect(Collectors.joining()));
        }
    }
}
