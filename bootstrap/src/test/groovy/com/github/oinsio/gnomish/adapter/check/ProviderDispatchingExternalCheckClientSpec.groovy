package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.adapter.check.github.GithubCheckClientFactory
import com.github.oinsio.gnomish.adapter.check.github.GithubCheckTokenException
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.time.Duration
import spock.lang.Specification

/**
 * {@link ProviderDispatchingExternalCheckClient}: per-check provider selection behind the engine's
 * single unchanged {@code ExternalCheckClient} port (FR3, FR5, FR6, FR15, design D10 of
 * add-plugin-architecture).
 *
 * <p>The claims under test are the ones that make the check port plugin-ready: a check reaches its
 * own provider's client; two providers coexist in one verify chain; a dormant provider is never
 * built, so its credential is never resolved; the pin contribution follows the same dispatch; and a
 * check naming a provider no jar serves fails naming the discovered set rather than silently
 * passing.
 */
class ProviderDispatchingExternalCheckClientSpec extends Specification {

    private static final Map GITHUB_SUBSECTION = [('api-url'): 'https://api.github.com', repo: 'acme/widgets']

    private static VerifyCheck.External check(String checkId, String provider) {
        new VerifyCheck.External(
                checkId, provider, Duration.ofSeconds(1), Duration.ofSeconds(5), VerifyCheck.TimeoutClass.QUALITY)
    }

    private static SecretsProvider providing(Map<String, String> secrets, List<String> resolved = []) { {
            name ->
            resolved << name; Optional.ofNullable(secrets[name])
        } as SecretsProvider
    }

    private static final Workspace WORKSPACE = new FakeWorkspace()

    private static Workspace workspace() {
        WORKSPACE
    }

    // FR5: a check resolves the provider's client from the registry and reads its verdict back —
    //     the engine sees one port, the composite decides who answers.
    def "a check is answered by the client of the provider it selects"() {
        given:
        def plugin = new PluginStandInCheckClientFactory(scripted: new PollStatus.Running())
        def dispatcher = new ProviderDispatchingExternalCheckClient(
                [(PluginStandInCheckClientFactory.PROVIDER): plugin],
                [(PluginStandInCheckClientFactory.PROVIDER): [endpoint: 'https://plugin.example']],
                providing([:]))

        expect:
        dispatcher.poll(check('ci', PluginStandInCheckClientFactory.PROVIDER), workspace()) instanceof PollStatus.Running
    }

    // FR6: "one stage runs external checks from different providers" — each check resolves its own
    //     provider independently, within the one verify chain.
    def "two checks in one chain reach two different providers"() {
        given:
        def first = new PluginStandInCheckClientFactory(scripted: new PollStatus.Pass())
        def second = new PluginStandInCheckClientFactory(scripted: PluginStandInCheckClientFactory.failing())
        def dispatcher = new ProviderDispatchingExternalCheckClient(
                ['alpha': first, 'beta': second],
                ['alpha': [endpoint: 'a'], 'beta': [endpoint: 'b']],
                providing([:]))

        expect:
        dispatcher.poll(check('a-check', 'alpha'), workspace()) instanceof PollStatus.Pass
        dispatcher.poll(check('b-check', 'beta'), workspace()) instanceof PollStatus.Fail
    }

    // FR3: a configured provider no check ever selects is never constructed, so its credential is
    //     never resolved — dormant providers stay entirely unexercised.
    def "a dormant provider's client is never built and its credential never resolved"() {
        given:
        def resolved = []
        def dispatcher = new ProviderDispatchingExternalCheckClient(
                ['alpha': new PluginStandInCheckClientFactory(),
                    'dormant': new PluginStandInCheckClientFactory()],
                ['alpha': [endpoint: 'a'],
                    'dormant': [endpoint: 'd', credential: 'DORMANT_TOKEN']],
                providing([ALPHA_TOKEN: 'tok', DORMANT_TOKEN: 'tok'], resolved))

        when:
        dispatcher.poll(check('ci', 'alpha'), workspace())

        then:
        !resolved.contains('DORMANT_TOKEN')
    }

    // FR3: the selected provider's client is built once and reused across polls — the engine's poll
    //     loop calls this port repeatedly, and rebuilding per poll would re-resolve credentials and
    //     drop any client-side state each time.
    def "the selected provider's client is built once and memoized across polls"() {
        given:
        def resolved = []
        def dispatcher = new ProviderDispatchingExternalCheckClient(
                ['alpha': new PluginStandInCheckClientFactory()],
                ['alpha': [endpoint: 'a', credential: 'ALPHA_TOKEN']],
                providing([ALPHA_TOKEN: 'tok'], resolved))

        when:
        3.times { dispatcher.poll(check('ci', 'alpha'), workspace()) }

        then:
        resolved == ['ALPHA_TOKEN']
    }

    // FR15: the pin contribution dispatches the same way the poll does, so the guard wrapping this
    //     seam unions the SELECTED provider's paths — for a discovered plugin exactly as for a
    //     bundled provider.
    def "the pin contribution follows the same dispatch"() {
        given:
        def dispatcher = new ProviderDispatchingExternalCheckClient(
                [(GithubCheckClientFactory.PROVIDER): new GithubCheckClientFactory(),
                    (PluginStandInCheckClientFactory.PROVIDER): new PluginStandInCheckClientFactory()],
                [(GithubCheckClientFactory.PROVIDER): GITHUB_SUBSECTION],
                providing([:]))

        expect:
        dispatcher.pinContributor().pinPaths(check('.github/workflows/ci.yml', 'github')) ==
                ['.github/workflows/ci.yml'] as Set
        dispatcher.pinContributor().pinPaths(check('quality-gate', PluginStandInCheckClientFactory.PROVIDER)) ==
                ['stand-in/quality-gate'] as Set
    }

    // FR13, M4: a manifest that named no provider reaches this composite carrying the github the
    //     loader recorded for it, so a manifest written before providers existed keeps reaching the
    //     provider it was written against.
    def "a check carrying the defaulted github selection reaches the github client"() {
        given:
        def resolved = []
        def dispatcher = new ProviderDispatchingExternalCheckClient(
                [(GithubCheckClientFactory.PROVIDER): new GithubCheckClientFactory()],
                [(GithubCheckClientFactory.PROVIDER): GITHUB_SUBSECTION],
                providing([GNOMISH_GITHUB_ACTIONS_TOKEN: 'tok'], resolved))

        when: 'the github client is built, then refuses this spec\'s workspace stand-in'
        dispatcher.poll(check('ci', GithubCheckClientFactory.PROVIDER), workspace())

        then: 'the github client was the one built — proven by the credential it resolved'
        thrown(IllegalArgumentException)
        resolved == [
            GithubCheckClientFactory.TOKEN_ENV_VAR
        ]
    }

    // FR26 of add-sandbox-core, under FR3's lazy construction: the fail-closed moment moves from
    //     wiring to first selection, but a credential that does not resolve still stops the check
    //     naming the secret — no poll ever runs against an unauthenticated client.
    def "an unresolvable credential fails closed at first selection, naming the secret"() {
        given:
        def dispatcher = new ProviderDispatchingExternalCheckClient(
                [(GithubCheckClientFactory.PROVIDER): new GithubCheckClientFactory()],
                [(GithubCheckClientFactory.PROVIDER): GITHUB_SUBSECTION],
                providing([:]))

        when:
        dispatcher.poll(check('ci', GithubCheckClientFactory.PROVIDER), workspace())

        then:
        def e = thrown(GithubCheckTokenException)
        e.message.contains(GithubCheckClientFactory.TOKEN_ENV_VAR)
    }

    // FR5: a check naming a provider no discovered jar serves fails naming the check, the provider
    //     and the discovered set — never a silent pass or an arbitrary substitute.
    def "a check naming an undiscovered provider fails naming it and the discovered set"() {
        given:
        def dispatcher = new ProviderDispatchingExternalCheckClient(
                [(GithubCheckClientFactory.PROVIDER): new GithubCheckClientFactory()],
                [(GithubCheckClientFactory.PROVIDER): GITHUB_SUBSECTION],
                providing([:]))

        when:
        dispatcher.poll(check('quality-gate', 'sonar'), workspace())

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('quality-gate')
        e.message.contains('sonar')
        e.message.contains('github')
    }
}
