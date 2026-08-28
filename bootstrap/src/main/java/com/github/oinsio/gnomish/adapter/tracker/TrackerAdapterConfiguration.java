package com.github.oinsio.gnomish.adapter.tracker;

import com.github.oinsio.gnomish.adapter.plugin.ProviderDiscoveryReport;
import com.github.oinsio.gnomish.app.TrackerAdapterFactory;
import com.github.oinsio.gnomish.app.TrackerSubsectionValidator;
import com.github.oinsio.gnomish.app.UsageException;
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Supplies the two composition-root registries keyed by {@code tracker.type} (tasks 5.13, 5.15):
 * the {@link TrackerAdapterFactory} registry {@code TakeCommand} resolves a live {@link
 * com.github.oinsio.gnomish.app.port.tracker.Tracker} from, and the {@link
 * TrackerSubsectionValidator} registry {@link
 * com.github.oinsio.gnomish.adapter.pipeline.PipelineLoader} delegates {@code tracker.<type>}
 * subsection content validation to (FR17). Both are keyed by the same discriminators, so an
 * adapter's config is validated at load time by the same validator that later builds its live
 * tracker.
 *
 * <p>Neither map is written out here any more: both come from one {@link TrackerAdapterDiscovery}
 * pass over {@code ServiceLoader}, with each provider's validator obtained from the provider itself
 * (FR1, design D1 of add-plugin-architecture). That is what removes the last hardwired {@code
 * Map.of(...)} provider registry from the tracker port — a new adapter now arrives as a jar carrying
 * a {@code META-INF/services} entry, with no edit here (M1).
 *
 * <p>Lives in {@code :bootstrap} but in the {@code adapter.tracker} package, and both halves of that
 * are deliberate. Building a port's registry is composition — the one thing only the composition
 * root may do (design D3's by-role rule) — and the package stays {@code adapter.tracker} because
 * {@code com.github.oinsio.gnomish.architecture.TrackerPortBoundarySpec} enforces that no class
 * outside it may depend on a class inside it (FR1 — "core compiles against the port alone"); only
 * the port-shaped {@code Map<String, TrackerAdapterFactory>} / {@code Map<String,
 * TrackerSubsectionValidator>} return types cross back into {@code app} / {@code adapter.pipeline}.
 *
 * <p>It reaches the context as an {@code @AutoConfiguration} listed in {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}, not by
 * component scan: the composition root scans only {@code com.github.oinsio.gnomish.app}, so a class
 * in an {@code adapter.*} package is never swept up by a scan (task 4.8, design D3). Registration
 * by name in a resource file rather than {@code @Import} is also what keeps the boundary rule above
 * intact — no class outside {@code adapter.tracker} names this one.
 *
 * <p>Neither factory is a Spring {@code @Bean} itself (design: no tracker is ever a shared bean,
 * matching the "constructed per invocation" rule for the tracker it produces); this configuration
 * only supplies the lookup maps. An unregistered {@code tracker.type} still resolves to {@code
 * TakeCommand}'s "no adapter registered for type '&lt;type&gt;'" {@link UsageException}, never a
 * missing-bean startup failure; an unregistered type at load time is reported by {@link
 * com.github.oinsio.gnomish.adapter.pipeline.TrackerSeamValidator} as an "unknown tracker type"
 * {@code ConfigError}.
 *
 * <p>Implements FR9, FR17 of add-tracker-port; FR1, M1 of add-plugin-architecture.
 */
@AutoConfiguration
public class TrackerAdapterConfiguration {

    /** The port name the startup discovery report is written under. */
    private static final String PORT = "tracker";

    /**
     * The discovered tracker providers, reported at startup with the artifact behind each entry
     * (NFR-O1, design D6 of add-plugin-architecture) — the visibility half of the trusted-classpath
     * posture, which is what makes a provider nobody meant to install visible before any task runs.
     */
    @Bean
    public Map<String, TrackerAdapterFactory> trackerAdapterRegistry(ClaimEpochBook claimEpochBook) {
        Map<String, TrackerAdapterFactory> discovered =
                ProviderDiscoveryReport.reported(PORT, TrackerAdapterDiscovery.discover());
        // FR13 of harden-task-branch-contract: every live tracker keeps the instance's claim-epoch
        // book current, so a tenure is recorded the moment it is issued and forgotten the moment it
        // ends — wherever the claim was made. Wrapping the registry rather than each command is what
        // makes that true of a command added later, too. The discovery report above is written over
        // the raw providers, so the operator still reads the artifact behind each entry, not a
        // decorator's name.
        return discovered.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> new EpochRecordingTrackerFactory(entry.getValue(), claimEpochBook)));
    }

    /**
     * The load-time subsection validators {@link
     * com.github.oinsio.gnomish.adapter.pipeline.PipelineLoader} delegates to, so a malformed {@code
     * tracker.github} subsection (e.g. a bad hex color) is a located load error aggregated with core
     * errors (FR17 — the "Adapter errors aggregate with core errors" scenario) rather than surfacing
     * only later as a GitHub API error during {@code take}.
     *
     * <p>Derived from {@link #trackerAdapterRegistry(ClaimEpochBook)} rather than discovered separately (design D1,
     * D3 of add-plugin-architecture): each provider exposes its own validator through {@link
     * TrackerAdapterFactory#subsectionValidator()}, so the two registries are keyed identically by
     * construction and cannot drift. A provider that grades no subsection content contributes no
     * entry — {@code inmemory}'s subsection is opaque, so an {@code inmemory} type with a subsection
     * is simply handed no validator.
     */
    @Bean
    public Map<String, TrackerSubsectionValidator> trackerSubsectionValidatorRegistry(
            Map<String, TrackerAdapterFactory> trackerAdapterRegistry) {
        var validators = new LinkedHashMap<String, TrackerSubsectionValidator>();
        trackerAdapterRegistry.forEach((type, factory) ->
                factory.subsectionValidator().ifPresent(validator -> validators.put(type, validator)));
        return Map.copyOf(validators);
    }
}
