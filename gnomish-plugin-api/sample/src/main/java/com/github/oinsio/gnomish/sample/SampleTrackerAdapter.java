package com.github.oinsio.gnomish.sample;

import com.github.oinsio.gnomish.app.TrackerAdapterFactory;
import com.github.oinsio.gnomish.app.TrackerSubsectionValidator;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The SPI half of the sample adapter: the {@link TrackerAdapterFactory} the factory resolves by
 * {@code tracker.type}, plus the {@link TrackerSubsectionValidator} that grades its own
 * adapter-owned config subsection.
 *
 * <p>Note what it takes to write this: one declared dependency. The {@code :domain} value types in
 * the signatures ({@link TrackerConfig}, {@link ConfigError}) arrive transitively through the api's
 * own `api` dependency, and the credential is read through the {@link SecretsProvider} port rather
 * than any secrets implementation (NFR-S1).
 */
public final class SampleTrackerAdapter implements TrackerAdapterFactory, TrackerSubsectionValidator {

    /** Public and no-arg, as {@code ServiceLoader} discovery requires (FR2 of add-plugin-architecture). */
    public SampleTrackerAdapter() {}

    @Override
    public String type() {
        return "sample";
    }

    @Override
    public Tracker create(SecretsProvider secrets, TrackerConfig config, String instanceId) {
        secrets.find("GNOMISH_SAMPLE_TOKEN");
        return new SampleTracker();
    }

    @Override
    public TaskRef expandRef(TrackerConfig config, String rawRef) {
        return new TaskRef(rawRef.startsWith("#") ? rawRef.substring(1) : rawRef);
    }

    @Override
    public List<String> credentialEnvVars(TrackerConfig config) {
        return List.of("GNOMISH_SAMPLE_TOKEN");
    }

    @Override
    public Optional<TrackerSubsectionValidator> subsectionValidator() {
        return Optional.of(this);
    }

    @Override
    public List<ConfigError> validate(String file, String where, Map<String, Object> subsection) {
        return List.of();
    }
}
