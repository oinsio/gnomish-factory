package com.github.oinsio.gnomish.sample;

import com.github.oinsio.gnomish.app.CheckClientFactory;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient;
import java.util.List;
import java.util.Map;

/**
 * The check-SPI half of the sample adapter: the {@link CheckClientFactory} the factory resolves
 * by a stage manifest's {@code provider}, mirroring {@link SampleTrackerAdapter} on the tracker
 * side.
 *
 * <p>Note what it takes to author an external check: one declared dependency. The client it
 * builds ({@link SampleExternalCheckClient}) learns the commit it verifies by narrowing the
 * engine-supplied {@code Workspace} to the api's {@code AttemptCommitWorkspace}, and sanitizes
 * its finding text through the api's {@code FindingsSanitizer} — both published contract types,
 * neither reachable from {@code :application}. That is the whole proof: if either ever leaves the
 * api, this module stops compiling (FR4, G1, design D4).
 *
 * <p>Implements FR4, G1 of close-plugin-api-compilability-gap.
 */
public final class SampleCheckAdapter implements CheckClientFactory {

    /** Public and no-arg, as {@code ServiceLoader} discovery requires (FR2 of add-plugin-architecture). */
    public SampleCheckAdapter() {}

    @Override
    public String provider() {
        return "sample";
    }

    @Override
    public ExternalCheckClient create(SecretsProvider secrets, Map<String, Object> subsection) {
        secrets.find("GNOMISH_SAMPLE_TOKEN");
        return new SampleExternalCheckClient();
    }

    @Override
    public List<String> credentialEnvVars(Map<String, Object> subsection) {
        return List.of("GNOMISH_SAMPLE_TOKEN");
    }
}
