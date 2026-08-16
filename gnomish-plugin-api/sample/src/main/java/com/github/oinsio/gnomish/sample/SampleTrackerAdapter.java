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

    private final SecretsProvider secrets;

    public SampleTrackerAdapter(SecretsProvider secrets) {
        this.secrets = secrets;
    }

    @Override
    public Tracker create(TrackerConfig config, String instanceId) {
        secrets.find("GNOMISH_SAMPLE_TOKEN");
        return new SampleTracker();
    }

    @Override
    public TaskRef expandRef(TrackerConfig config, String rawRef) {
        return new TaskRef(rawRef.startsWith("#") ? rawRef.substring(1) : rawRef);
    }

    @Override
    public List<String> credentialEnvVars() {
        return List.of("GNOMISH_SAMPLE_TOKEN");
    }

    @Override
    public List<ConfigError> validate(String file, String where, Map<String, Object> subsection) {
        return List.of();
    }
}
