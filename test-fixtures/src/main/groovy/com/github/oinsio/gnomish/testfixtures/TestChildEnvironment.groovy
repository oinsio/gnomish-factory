package com.github.oinsio.gnomish.testfixtures

import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment

/**
 * The one spelling of what a test-spawned child process may see of the test JVM's environment
 * (design D14 of make-checkpoint-gate-durable): a spawner clears its {@link ProcessBuilder}'s
 * inherited environment through {@link #cleared}, then puts only the test-owned variables it sets
 * itself ({@code GNOMISH_FAKE_*}, {@code GNOMISH_HOME}, {@code GNOMISH_DECISION_FILE}).
 *
 * <p>The failure this exists for: a test run inside a gnome round inherited the round's
 * {@code GNOMISH_DECISION_FILE}, and the fake agent a fixture spawned wrote its scenario's
 * decision into the round's real decision file. A {@code GNOMISH_*} variable of the parent never
 * reaches a child through this helper — none is on {@link #NAMES} and none matches a prefix.
 *
 * <p>Kept: the production host base set ({@link HostTaskExecutionEnvironment#BASE_ENV_NAMES}),
 * the build's git test configuration (design D11 of own-git-transfer-argv), {@code JAVA_HOME}
 * for a spawned {@code java -jar}, and the Docker client variables, so a spawned factory reaches
 * the same daemon the test JVM does. Values are composed through the production owner,
 * {@link ChildEnvAllowlist}, over the test JVM's own environment.
 *
 * <p>Implements FR22, M10 of make-checkpoint-gate-durable.
 */
final class TestChildEnvironment {

    /** The exact names a child keeps, each only when present in the test JVM's environment. */
    static final List<String> NAMES = (HostTaskExecutionEnvironment.BASE_ENV_NAMES + [
        'GIT_CONFIG_GLOBAL',
        'GIT_CONFIG_COUNT',
        'GIT_CONFIG_KEY_0',
        'GIT_CONFIG_VALUE_0',
        'JAVA_HOME',
        'DOCKER_HOST',
        'DOCKER_CONFIG',
        'DOCKER_CERT_PATH',
        'DOCKER_TLS_VERIFY',
    ]).asImmutable()

    /** Name prefixes a child keeps: Testcontainers' own client configuration. */
    static final List<String> PREFIXES = ['TESTCONTAINERS_'].asImmutable()

    private TestChildEnvironment() {}

    /**
     * Clears {@code builder}'s inherited environment and puts back {@link #NAMES} and every
     * {@link #PREFIXES} match present in the test JVM's environment.
     *
     * @return {@code builder}'s environment, for the caller's own test-owned variables
     */
    static Map<String, String> cleared(ProcessBuilder builder) {
        Map<String, String> parent = System.getenv()
        List<String> names = new ArrayList<>(NAMES)
        names.addAll(parent.keySet().findAll { name ->
            PREFIXES.any {
                name.startsWith(it)
            }
        }.sort())
        Map<String, String> environment = builder.environment()
        environment.clear()
        environment.putAll(ChildEnvAllowlist.none().compose(names, [:]))
        environment
    }
}
