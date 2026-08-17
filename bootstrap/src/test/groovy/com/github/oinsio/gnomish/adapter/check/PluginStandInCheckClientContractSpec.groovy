package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.engine.port.contract.ExternalCheckClientContract
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.time.Duration

/**
 * FR3, FR5 of add-plugin-architecture: a second, test-only check provider passes the very same
 * port-level contract suite the bundled adapters pass. That is the no-special-casing claim made
 * checkable — the engine's poll loop relies on nothing github-specific, so a provider built entirely
 * outside this build satisfies the port by satisfying this suite.
 *
 * <p>The client under test is reached the way production reaches one: through the SPI factory's
 * {@code create(secrets, subsection)}, not by constructing the client directly.
 */
class PluginStandInCheckClientContractSpec extends ExternalCheckClientContract {

    @Override
    protected Optional<PollStatus> arrange(PollVariant variant) {
        def factory = new PluginStandInCheckClientFactory(scripted: scripted(variant))
        def client = factory.create({ _ ->
            Optional.of('secret')
        }, [endpoint: 'https://plugin.example'])
        Optional.of(client.poll(
                        new VerifyCheck.External(
                                'ci', PluginStandInCheckClientFactory.PROVIDER, Duration.ofSeconds(1),
                                Duration.ofSeconds(5), VerifyCheck.TimeoutClass.QUALITY),
                        new FakeWorkspace()))
    }

    private static PollStatus scripted(PollVariant variant) {
        switch (variant) {
                    case PollVariant.PASS -> new PollStatus.Pass()
                    case PollVariant.FAIL_WITH_FINDINGS -> new PollStatus.Fail(List.of(new Finding('stand-in finding', null, null)))
                    case PollVariant.RUNNING -> new PollStatus.Running()
                    case PollVariant.CANNOT_VERIFY -> new PollStatus.CannotVerify('plugin unreachable', 'no route to host')
                }
    }
}
