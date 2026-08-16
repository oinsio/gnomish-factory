package com.github.oinsio.gnomish.adapter.tracker;

import com.github.oinsio.gnomish.adapter.tracker.github.GithubTrackerAdapterFactory;
import com.github.oinsio.gnomish.adapter.tracker.github.GithubTrackerSubsectionValidator;
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerAdapterFactory;
import com.github.oinsio.gnomish.app.TrackerAdapterFactory;
import com.github.oinsio.gnomish.app.TrackerSubsectionValidator;
import com.github.oinsio.gnomish.app.UsageException;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import java.util.Map;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Supplies the two composition-root registries keyed by {@code tracker.type} (tasks 5.13, 5.15):
 * the {@link TrackerAdapterFactory} registry {@code TakeCommand} resolves a live {@link
 * com.github.oinsio.gnomish.app.port.tracker.Tracker} from, and the {@link
 * TrackerSubsectionValidator} registry {@link
 * com.github.oinsio.gnomish.adapter.pipeline.PipelineLoader} delegates {@code tracker.<type>}
 * subsection content validation to (FR17). Both spell {@code "github"} / {@code "inmemory"}
 * identically, so an adapter's config is validated at load time by the same validator that later
 * builds its live tracker.
 *
 * <p>Lives in {@code :bootstrap} but in the {@code adapter.tracker} package, and both halves of
 * that are deliberate. It is composition — a registry naming concrete adapters is the one thing
 * only the composition root may do (design D3's by-role rule) — and since task 10.1 split {@code
 * :adapters:github} out of {@code :adapters}, {@code :bootstrap} is also the only module that sees
 * both vendors at once: neither adapter module depends on the other, which is exactly the
 * sibling isolation the vertical split buys (FR2, M4). The package stays {@code adapter.tracker}
 * because {@code com.github.oinsio.gnomish.architecture.TrackerPortBoundarySpec} enforces that no
 * class outside it may depend on a class inside it (FR1 — "core compiles against the port alone"),
 * so the one class naming both concrete factories and the concrete {@link
 * GithubTrackerSubsectionValidator} has to sit at the same edge they do; only the port-shaped
 * {@code Map<String, TrackerAdapterFactory>} / {@code Map<String, TrackerSubsectionValidator>}
 * return types cross back into {@code app} / {@code adapter.pipeline}.
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
 * <p>Implements FR9, FR17 of add-tracker-port.
 */
@AutoConfiguration
public class TrackerAdapterConfiguration {

    @Bean
    public Map<String, TrackerAdapterFactory> trackerAdapterRegistry(SecretsProvider secretsProvider) {
        return Map.of(
                "github", new GithubTrackerAdapterFactory(secretsProvider),
                "inmemory", new InMemoryTrackerAdapterFactory());
    }

    /**
     * The load-time subsection validators {@link
     * com.github.oinsio.gnomish.adapter.pipeline.PipelineLoader} delegates to, so a malformed {@code
     * tracker.github} subsection (e.g. a bad hex color) is a located load error aggregated with core
     * errors (FR17 — the "Adapter errors aggregate with core errors" scenario) rather than surfacing
     * only later as a GitHub API error during {@code take}. Keyed identically to {@link
     * #trackerAdapterRegistry(SecretsProvider)}; {@code inmemory} needs no content validator (its
     * subsection is opaque), so an {@code inmemory} type with a subsection is simply handed no
     * validator.
     */
    @Bean
    public Map<String, TrackerSubsectionValidator> trackerSubsectionValidatorRegistry() {
        return Map.of("github", new GithubTrackerSubsectionValidator());
    }
}
