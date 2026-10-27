package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.TrackerReadVerdict
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import com.github.oinsio.gnomish.untrustedtext.Provenance

/**
 * {@link GithubTrackerAdapterFactory#probeRead} (FR3 of add-doctor-command, design D7): the
 * public SPI override, driven with the credential resolved through the secrets seam, against a
 * WireMock {@code acme/widgets} — one label listing over the labels {@code create} provisions,
 * classified by {@link GithubLabelProvisioner#assess} and mapped into the api's
 * {@link TrackerReadVerdict}; no write, and a 401 answered once.
 *
 * Implements FR3 of add-doctor-command.
 */
class GithubTrackerReadProbeSpec extends GithubLabelRepoSpecification {

    private TrackerConfig configWith(Map<String, Object> extra = [:]) {
        new TrackerConfig('github', 3, ['api-url': wireMock.baseUrl(), repo: 'acme/widgets'] + extra)
    }

    private TrackerReadVerdict probe(TrackerConfig config, Map<String, String> secrets = [GNOMISH_GITHUB_TOKEN: 'tok-1']) {
        new GithubTrackerAdapterFactory().probeRead(new MapSecretsProvider(secrets), config)
    }

    def "FR3: a repo lacking the labels is read with one GET and no POST, naming the labels startup will create"() {
        given:
        listingAnswers(200, '[{"name":"gnomish:working"},{"name":"bug"}]')

        when:
        def verdict = probe(configWith())

        then:
        verdict == new TrackerReadVerdict.ReadConfirmed([
            'gnomish:ready',
            'gnomish:needs-human',
            'gnomish:delivered'
        ])
        wireMock.verify(1, getRequestedFor(urlEqualTo(LISTING)).withHeader('Authorization', equalTo('Bearer tok-1')))
        wireMock.verify(0, postRequestedFor(anyUrl()))
        wireMock.verify(1, anyRequestedFor(anyUrl()))
    }

    def "FR3: the probe reports the configured label names, the ones create provisions"() {
        given:
        listingAnswers(200, '[{"name":"gnomish:ready"},{"name":"gnomish:working"},{"name":"gnomish:needs-human"},{"name":"gnomish:delivered"}]')

        expect:
        probe(configWith(labels: [ready: [name: 'custom:ready']])) == new TrackerReadVerdict.ReadConfirmed(['custom:ready'])
    }

    def "FR3: the probe resolves a credential a connection profile renamed"() {
        given:
        listingAnswers(200, '[]')

        when:
        probe(configWith(credential: 'ACME_TOKEN'), [ACME_TOKEN: 'tok-2'])

        then:
        wireMock.verify(1, getRequestedFor(urlEqualTo(LISTING)).withHeader('Authorization', equalTo('Bearer tok-2')))
    }

    def "FR3: a refused listing (HTTP #status) is refused with the cause startup names, never retried"() {
        given:
        listingAnswers(status, '{"message":"no"}')

        when:
        def verdict = probe(configWith())

        then:
        verdict == new TrackerReadVerdict.Refused(status, cause, Optional.empty())
        wireMock.verify(1, anyRequestedFor(anyUrl()))

        where:
        status || cause
        404 || 'repo not found or not visible to this token (likely cause: stale binding, e.g. a fork pointing at a repo the token cannot see)'
        403 || 'token lacks write access to this repo'
        401 || 'unexpected HTTP 401'
    }

    def "FR3: a 403 hands on GitHub's accepted-permissions header as tracker text"() {
        given:
        listingAnswers(403, '{}', ['X-Accepted-GitHub-Permissions': 'issues=write; metadata=read'])

        when:
        def verdict = probe(configWith()) as TrackerReadVerdict.Refused

        then:
        verdict.acceptedPermissions().get().provenance() == Provenance.TRACKER
        verdict.acceptedPermissions().get().forLog() == 'issues=write; metadata=read'
    }

    def "FR3: a 2xx body that is not a listing is malformed, with the cause startup names"() {
        given:
        listingAnswers(200, 'not json {')

        expect:
        probe(configWith()) == new TrackerReadVerdict.Malformed('could not parse existing-labels response')
    }

    def "FR3: a missing credential refuses before any request, as refuseForeignRef does"() {
        when:
        probe(configWith(), [:])

        then:
        def ex = thrown(GithubTrackerConfigException)
        ex.message.contains('GNOMISH_GITHUB_TOKEN')
        wireMock.verify(0, anyRequestedFor(anyUrl()))
    }
}
