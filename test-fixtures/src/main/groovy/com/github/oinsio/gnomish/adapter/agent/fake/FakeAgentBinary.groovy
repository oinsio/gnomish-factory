package com.github.oinsio.gnomish.adapter.agent.fake

import java.nio.file.Path

/**
 * Locates the fake agent script ({@code fake-agent/fake-agent.sh}, see the README next to it) and
 * exposes it as a ready-to-run command prefix.
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
 * <p>The script is resolved from the {@code fakeAgentDir} system property, not from the
 * classpath: the fixtures live in {@code :test-fixtures}, whose resources reach a consumer inside
 * a jar, and neither {@code sh <path>} nor {@link #scenariosDir} can work against a {@code jar:}
 * URI. Every test task that uses the fake agent sets the property to the one on-disk copy — the
 * same idiom {@code referenceDumpDir} already uses for the committed reference dumps (task 5.1 of
 * split-into-modules).
 *
 * <p>Not production code: test-support only, never PIT-mutated.
 */
final class FakeAgentBinary {

    /** System property naming the on-disk {@code fake-agent} directory; set by every test task. */
    private static final String DIR_PROPERTY = 'fakeAgentDir'

    private FakeAgentBinary() {}

    /**
     * @return the on-disk {@code fake-agent} directory: the script, the scenario library and the
     *     README
     */
    static Path rootDir() {
        String configured = System.getProperty(DIR_PROPERTY)
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
            "test task must set the '${DIR_PROPERTY}' system property (see the build scripts)")
        }
        Path dir = Path.of(configured)
        if (!java.nio.file.Files.isDirectory(dir)) {
            throw new IllegalStateException("'${DIR_PROPERTY}' does not name a directory: ${dir}")
        }
        dir
    }

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
        Path script = rootDir().resolve('fake-agent.sh')
        if (!java.nio.file.Files.isRegularFile(script)) {
            throw new IllegalStateException("fake agent script not found at ${script} — has it moved?")
        }
        script
    }
}
