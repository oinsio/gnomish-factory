package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.untrustedtext.Provenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * GithubLabelProvisioner.assess (FR3 of add-doctor-command, design D7): the read-only probe of the
 * tracker binding — one label listing, no write, a verdict naming the labels startup will create
 * or the cause startup would refuse with, and GitHub's accepted-permissions header on a 403.
 *
 * Implements FR3 of add-doctor-command.
 */
class GithubLabelAssessmentSpec extends GithubLabelRepoSpecification {

    // Scenario: tracker/github-tracker / Read probe lists and writes nothing
    def "the probe of a repo lacking the labels lists once, writes nothing and names the missing labels"() {
        given:
        listingAnswers(200, '[{"name":"gnomish:working"},{"name":"bug"}]')

        when:
        def verdict = newProvisioner().assess('acme', 'widgets', defaultDefs())

        then:
        verdict == new GithubLabelAssessment.Listed([
            'gnomish:ready',
            'gnomish:needs-human',
            'gnomish:delivered'
        ])
        verdict.failureMessage('acme/widgets').isEmpty()
        wireMock.verify(1, getRequestedFor(urlEqualTo(LISTING)))
        wireMock.verify(0, postRequestedFor(anyUrl()))
        wireMock.verify(1, anyRequestedFor(anyUrl()))
    }

    def "a repo holding every configured label reads with nothing missing"() {
        given:
        listingAnswers(200, '[{"name":"gnomish:ready"},{"name":"gnomish:working"},{"name":"gnomish:needs-human"},{"name":"gnomish:delivered"}]')

        expect:
        newProvisioner().assess('acme', 'widgets', defaultDefs()) == new GithubLabelAssessment.Listed([])
    }

    def "a refused listing (HTTP #status) carries the cause provisioning names for that status"() {
        given:
        listingAnswers(status, '{"message":"no"}')

        when:
        def verdict = newProvisioner().assess('acme', 'widgets', defaultDefs())

        then:
        verdict == new GithubLabelAssessment.Refused(status, cause, Optional.empty())
        verdict.failureMessage('acme/widgets') == Optional.of(
                "Label provisioning failed for acme/widgets: could not list existing labels (HTTP $status) - $cause".toString())
        wireMock.verify(1, anyRequestedFor(anyUrl()))

        where:
        status || cause
        404 || 'repo not found or not visible to this token (likely cause: stale binding, e.g. a fork pointing at a repo the token cannot see)'
        403 || 'token lacks write access to this repo'
        422 || 'token lacks write access to this repo (unprocessable request)'
        401 || 'unexpected HTTP 401'
    }

    def "a 401 is answered once: the probe never retries bad credentials"() {
        given:
        listingAnswers(401, '{"message":"Bad credentials"}')

        when:
        newProvisioner().assess('acme', 'widgets', defaultDefs())

        then:
        wireMock.verify(1, getRequestedFor(urlEqualTo(LISTING)))
    }

    def "a 403 carries GitHub's accepted-permissions header verbatim, as tracker text"() {
        given:
        listingAnswers(403, '{}', ['X-Accepted-GitHub-Permissions': 'issues=write; metadata=read'])

        when:
        def verdict = newProvisioner().assess('acme', 'widgets', defaultDefs()) as GithubLabelAssessment.Refused

        then:
        verdict.acceptedPermissions() == Optional.of(UntrustedText.tracker('issues=write; metadata=read'))
        verdict.acceptedPermissions().get().provenance() == Provenance.TRACKER
    }

    def "the accepted-permissions header is read on a 403 only"() {
        given:
        listingAnswers(404, '{}', ['X-Accepted-GitHub-Permissions': 'issues=write'])

        expect:
        (newProvisioner().assess('acme', 'widgets', defaultDefs()) as GithubLabelAssessment.Refused)
                .acceptedPermissions().isEmpty()
    }

    def "a 403 without the header carries no accepted permissions"() {
        given:
        listingAnswers(403, '{}')

        expect:
        (newProvisioner().assess('acme', 'widgets', defaultDefs()) as GithubLabelAssessment.Refused)
                .acceptedPermissions().isEmpty()
    }

    def "a 2xx body that is not a listing is malformed, refused with the parse message"() {
        given:
        listingAnswers(200, 'not json {')

        when:
        def verdict = newProvisioner().assess('acme', 'widgets', defaultDefs())

        then:
        verdict == new GithubLabelAssessment.Malformed()
        verdict.failureMessage('acme/widgets') ==
                Optional.of('Label provisioning failed for acme/widgets: could not parse existing-labels response')
    }

    def "the listed verdict holds its own copy of the missing names"() {
        given:
        def names = ['gnomish:ready']
        def verdict = new GithubLabelAssessment.Listed(names)

        when:
        names << 'gnomish:working'

        then:
        verdict.missingLabelNames() == ['gnomish:ready']
    }
}
