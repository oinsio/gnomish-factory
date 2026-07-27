package com.github.oinsio.gnomish.adapter.tracker;

import com.github.oinsio.gnomish.adapter.pipeline.TrackerSubsectionValidator;
import com.github.oinsio.gnomish.adapter.tracker.github.GithubTrackerAdapterFactory;
import com.github.oinsio.gnomish.adapter.tracker.github.GithubTrackerSubsectionValidator;
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerAdapterFactory;
import com.github.oinsio.gnomish.app.TrackerAdapterFactory;
import com.github.oinsio.gnomish.app.UsageException;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
 * <p>Lives in {@code adapter.tracker} rather than {@code app} (unlike most other {@code
 * @Configuration} classes, e.g. {@code ManualRunConfiguration}): {@code
 * com.github.oinsio.gnomish.architecture.TrackerPortBoundarySpec} enforces that no class outside
 * {@code adapter.tracker} may depend on a class inside it (FR1 — "core compiles against the port
 * alone"), so the one composition-root class that names both concrete adapter factories and the
 * concrete {@link GithubTrackerSubsectionValidator} has to sit at the same edge they do; only the
 * port-shaped {@code Map<String, TrackerAdapterFactory>} / {@code Map<String,
 * TrackerSubsectionValidator>} return types cross back into {@code app} / {@code adapter.pipeline}.
 * Springs's {@code @SpringBootApplication} component scan (rooted at {@code
 * com.github.oinsio.gnomish}) still discovers this class here.
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
@Configuration
public class TrackerAdapterConfiguration {

    @Bean
    public Map<String, TrackerAdapterFactory> trackerAdapterRegistry() {
        return Map.of(
                "github", new GithubTrackerAdapterFactory(),
                "inmemory", new InMemoryTrackerAdapterFactory());
    }

    /**
     * The load-time subsection validators {@link
     * com.github.oinsio.gnomish.adapter.pipeline.PipelineLoader} delegates to, so a malformed {@code
     * tracker.github} subsection (e.g. a bad hex color) is a located load error aggregated with core
     * errors (FR17 — the "Adapter errors aggregate with core errors" scenario) rather than surfacing
     * only later as a GitHub API error during {@code take}. Keyed identically to {@link
     * #trackerAdapterRegistry()}; {@code inmemory} needs no content validator (its subsection is
     * opaque), so an {@code inmemory} type with a subsection is simply handed no validator.
     */
    @Bean
    public Map<String, TrackerSubsectionValidator> trackerSubsectionValidatorRegistry() {
        return Map.of("github", new GithubTrackerSubsectionValidator());
    }
}
