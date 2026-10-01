package com.github.oinsio.gnomish.config;

import static com.github.oinsio.gnomish.operatorconfig.Level.ANY;
import static com.github.oinsio.gnomish.operatorconfig.Level.HOST;
import static com.github.oinsio.gnomish.operatorconfig.Level.PROJECT;
import static com.github.oinsio.gnomish.operatorconfig.Level.SANDBOX_BOUNDARY;

import com.github.oinsio.gnomish.operatorconfig.ConfigLevel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

/**
 * The fixture record set {@code ConfigLevelsSpec} derives its key → level table from (design D4):
 * dashed names, a {@code @Name} override, a nested record, map and list subtrees, and the shapes
 * {@link ConfigLevels} refuses.
 *
 * <p>Declared in Java rather than inside the spec because IntelliJ's Groovy support treats a Groovy
 * record header as a parameter list and flags {@link ConfigLevel}, a {@code RECORD_COMPONENT}-only
 * annotation, as not applicable there; the Groovy compiler places it correctly, but the false error
 * would sit on the spec permanently.
 *
 * <p>FR4, FR6 of add-project-registry.
 */
final class ConfigLevelsFixtures {

    private ConfigLevelsFixtures() {}

    @ConfigurationProperties("fixture")
    record Root(
            @ConfigLevel(ANY) Duration gitNetworkTimeout,
            @ConfigLevel(PROJECT) Map<String, Map<String, Object>> check,
            Inner inner) {}

    record Inner(
            @ConfigLevel(HOST) String dockerRuntime,
            @ConfigLevel(SANDBOX_BOUNDARY) List<String> allowlist) {}

    @ConfigurationProperties(prefix = "fixture.bindings")
    record Renamed(
            @ConfigLevel(SANDBOX_BOUNDARY) @Name("default") String defaultBinding,
            @ConfigLevel(SANDBOX_BOUNDARY) String[] extra) {}

    @ConfigurationProperties("fixture.bad")
    record Unlevelled(String missing) {}

    record NotProperties(@ConfigLevel(ANY) String any) {}

    @ConfigurationProperties("fixture.plain")
    static final class NotARecord {}
}
