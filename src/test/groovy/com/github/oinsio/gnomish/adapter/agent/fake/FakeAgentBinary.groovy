package com.github.oinsio.gnomish.adapter.agent.fake

import java.nio.file.Path

/**
 * Locates the fake agent script (test resource {@code /fake-agent/fake-agent.sh},
 * see the README next to it) and exposes it as a ready-to-run command prefix.
 *
 * <p>Task 2.1, FR15, D11 of add-agent-executor: this is the seam a future
 * {@code FactoryProperties}-backed CLI launcher (task 4.1) is expected to point
 * at in tests — {@link #commandPrefix()} returns exactly the {@code ['sh',
 * '<path>']} prefix that adapter tests prepend to their own transport args
 * ({@code -p}, {@code --output-format stream-json --verbose}, {@code --model},
 * ...). Shelling out via {@code sh} rather than invoking the script path
 * directly follows this project's existing process-runner convention
 * ({@code ShellCommandCheckRunner}, {@code CommandProcessRunner}): Gradle's
 * resource copy (and a fresh git checkout) does not reliably preserve the
 * executable bit, so relying on it would make the harness flaky across
 * environments.
 *
 * <p>Not production code: test-support only, never PIT-mutated.
 */
final class FakeAgentBinary {

    private static final String SCRIPT_RESOURCE = '/fake-agent/fake-agent.sh'

    private FakeAgentBinary() {}

    /**
     * @return the scenarios directory sibling to the script, for callers that
     *     want to inspect or add scenario fixtures directly
     */
    static Path scenariosDir() {
        scriptPath().resolveSibling('scenarios')
    }

    /**
     * @return the {@code ['sh', '<absolute script path>']} command prefix; append
     *     any transport args an adapter-under-test would pass to the real CLI
     */
    static List<String> commandPrefix() {
        [
            'sh',
            scriptPath().toAbsolutePath().toString()
        ]
    }

    private static Path scriptPath() {
        URL resource = FakeAgentBinary.getResource(SCRIPT_RESOURCE)
        if (resource == null) {
            throw new IllegalStateException(
            "test resource '${SCRIPT_RESOURCE}' not found — has the fake agent script moved?")
        }
        Path.of(resource.toURI())
    }
}
