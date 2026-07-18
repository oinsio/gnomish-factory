package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.fake.FakeAgentBinary

/**
 * Shared seam for CLI-adapter specs that point {@link FactoryProperties} at
 * the fake agent binary (task 2, design D11 of add-agent-executor): {@code
 * FactoryProperties.agentCliBinary()} is a single token, but the fake's
 * script must be invoked as {@code sh <path>} (its executable bit is not
 * reliably preserved by Gradle's resource copy / a fresh checkout — see
 * {@code fake-agent/README.md}) and needs {@code GNOMISH_FAKE_SCENARIO} set
 * before it runs. This wraps both into one tiny generated shell script so a
 * real {@link AgentProcessLauncher}/{@link CliStageExecutor} can invoke it as
 * a plain binary path, mirroring {@code AgentProcessLauncherSpec}'s
 * {@code fakeAgentProperties} helper.
 *
 * <p>Not production code: test-support only, never PIT-mutated.
 */
final class FakeAgentSupport {

    private FakeAgentSupport() {}

    /**
     * @param scenario the {@code GNOMISH_FAKE_SCENARIO} name to hardcode into
     *     the generated wrapper script
     * @param envPassthrough the {@code agentCliEnvPassthrough} list; defaults
     *     to empty
     * @return {@link FactoryProperties} whose {@code agentCliBinary} is the
     *     generated wrapper script's path
     */
    static FactoryProperties propertiesFor(String scenario, List<String> envPassthrough = []) {
        def wrapper = File.createTempFile('fake-agent-wrapper', '.sh')
        wrapper.text = "#!/bin/sh\nexport GNOMISH_FAKE_SCENARIO='${scenario}'\nexec sh '${FakeAgentBinary.commandPrefix()[1]}' \"\$@\"\n"
        wrapper.setExecutable(true)
        wrapper.deleteOnExit()
        new FactoryProperties('factory-01', wrapper.absolutePath, envPassthrough)
    }
}
