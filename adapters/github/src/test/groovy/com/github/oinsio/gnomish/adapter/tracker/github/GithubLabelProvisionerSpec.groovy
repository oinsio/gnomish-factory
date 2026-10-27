package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo


/**
 * GithubLabelProvisioner (FR5, NFR-R4 of add-tracker-port): verifies the
 * "Idempotent label provisioning as startup smoke test" requirement of the
 * github-tracker spec — creating only missing labels, never recoloring an
 * existing one, and failing loud with the repo name when the token cannot
 * write to the configured repo.
 *
 * Implements FR5, NFR-R4 of add-tracker-port; FR3 of add-doctor-command.
 */
class GithubLabelProvisionerSpec extends GithubLabelRepoSpecification {

    def "creates every missing configured label with its color and description"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/labels?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/labels'))
                .willReturn(aResponse().withStatus(201).withBody('{}')))
        def provisioner = newProvisioner()

        when:
        provisioner.provision('acme', 'widgets', defaultDefs())

        then:
        defaultDefs().each { d ->
            wireMock.verify(postRequestedFor(urlEqualTo('/repos/acme/widgets/labels'))
            .withRequestBody(equalToJson(
                    '{"name":"' + d.name() + '","color":"' + d.color() + '","description":"' + d.description() + '"}')))
        }
    }

    // Scenario: tracker/github-tracker / Second start changes nothing
    def "second start against the same repo issues no label mutations"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/labels?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"name":"gnomish:ready","color":"2ea44f"},
                          {"name":"gnomish:working","color":"1f6feb"},
                          {"name":"gnomish:needs-human","color":"d73a4a"},
                          {"name":"gnomish:delivered","color":"8250df"}
                        ]
                        ''')))
        def provisioner = newProvisioner()

        when:
        provisioner.provision('acme', 'widgets', defaultDefs())

        then:
        wireMock.verify(0, postRequestedFor(urlEqualTo('/repos/acme/widgets/labels')))
    }

    // Scenario: tracker/github-tracker / Operator recolored a label
    def "an existing label with a different color than configured is left untouched"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/labels?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"name":"gnomish:ready","color":"ff0000"},
                          {"name":"gnomish:working","color":"1f6feb"},
                          {"name":"gnomish:needs-human","color":"d73a4a"},
                          {"name":"gnomish:delivered","color":"8250df"}
                        ]
                        ''')))
        def provisioner = newProvisioner()

        when:
        provisioner.provision('acme', 'widgets', defaultDefs())

        then:
        wireMock.verify(0, postRequestedFor(urlEqualTo('/repos/acme/widgets/labels')))
    }

    def "creates only the labels missing from an already partially-provisioned repo"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/labels?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"name":"gnomish:ready","color":"2ea44f"}
                        ]
                        ''')))
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/labels'))
                .willReturn(aResponse().withStatus(201).withBody('{}')))
        def provisioner = newProvisioner()

        when:
        provisioner.provision('acme', 'widgets', defaultDefs())

        then:
        wireMock.verify(0, postRequestedFor(urlEqualTo('/repos/acme/widgets/labels'))
                .withRequestBody(equalToJson('{"name":"gnomish:ready","color":"2ea44f","description":"Gnomish factory: task ready to be claimed"}')))
        wireMock.verify(1, postRequestedFor(urlEqualTo('/repos/acme/widgets/labels'))
                .withRequestBody(equalToJson('{"name":"gnomish:working","color":"1f6feb","description":"Gnomish factory: task claimed and in progress"}')))
        wireMock.verify(1, postRequestedFor(urlEqualTo('/repos/acme/widgets/labels'))
                .withRequestBody(equalToJson('{"name":"gnomish:needs-human","color":"d73a4a","description":"Gnomish factory: task escalated, waiting on a human"}')))
        wireMock.verify(1, postRequestedFor(urlEqualTo('/repos/acme/widgets/labels'))
                .withRequestBody(equalToJson('{"name":"gnomish:delivered","color":"8250df","description":"Gnomish factory: task delivered"}')))
    }

    // Scenario: tracker/github-tracker / Fork with stale binding dies at startup
    def "a 404 on label creation (fork with a stale binding) fails startup naming the repo"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/labels?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/labels'))
                .willReturn(aResponse().withStatus(404).withBody('{"message":"Not Found"}')))
        def provisioner = newProvisioner()

        when:
        provisioner.provision('acme', 'widgets', defaultDefs())

        then:
        def ex = thrown(GithubLabelProvisioningException)
        ex.message.contains('acme/widgets')
    }

    def "a 403 on label creation (no write scope) fails startup naming the repo"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/labels?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/labels'))
                .willReturn(aResponse().withStatus(403).withBody('{"message":"Forbidden"}')))
        def provisioner = newProvisioner()

        when:
        provisioner.provision('acme', 'widgets', defaultDefs())

        then:
        def ex = thrown(GithubLabelProvisioningException)
        ex.message.contains('acme/widgets')
    }

    def "a failure to read existing labels also fails startup naming the repo"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/labels?per_page=100'))
                .willReturn(aResponse().withStatus(404).withBody('{"message":"Not Found"}')))
        def provisioner = newProvisioner()

        when:
        provisioner.provision('acme', 'widgets', defaultDefs())

        then:
        def ex = thrown(GithubLabelProvisioningException)
        ex.message.contains('acme/widgets')
    }

    // FR3 of add-doctor-command (design D7): provision is built on assess, so the two classify one
    // listing with one code — provision refuses exactly when assess fails, with its message.
    def "provision refuses exactly when assess fails, for listing status #status"() {
        given:
        listingAnswers(status, body)
        wireMock.stubFor(post(urlEqualTo(CREATION)).willReturn(aResponse().withStatus(201).withBody('{}')))
        def provisioner = newProvisioner()

        when:
        def verdict = provisioner.assess('acme', 'widgets', defaultDefs())

        then: 'assess lists once and writes nothing'
        verdict.class == expected
        wireMock.verify(1, getRequestedFor(urlEqualTo(LISTING)))
        wireMock.verify(0, postRequestedFor(urlEqualTo(CREATION)))

        when:
        Exception refusal = null
        try {
            provisioner.provision('acme', 'widgets', defaultDefs())
        } catch (GithubLabelProvisioningException e) {
            refusal = e
        }

        then:
        (refusal != null) == (expected != GithubLabelAssessment.Listed)
        refusal?.message == verdict.failureMessage('acme/widgets').orElse(null)
        wireMock.verify(refusal == null ? 4 : 0, postRequestedFor(urlEqualTo(CREATION)))

        where:
        status | body || expected
        200 | '[]' || GithubLabelAssessment.Listed
        201 | '[]' || GithubLabelAssessment.Listed
        299 | '[]' || GithubLabelAssessment.Listed
        200 | 'not json {' || GithubLabelAssessment.Malformed
        300 | '[]' || GithubLabelAssessment.Refused
        401 | '{}' || GithubLabelAssessment.Refused
        403 | '{}' || GithubLabelAssessment.Refused
        404 | '{}' || GithubLabelAssessment.Refused
        418 | '{}' || GithubLabelAssessment.Refused
        422 | '{}' || GithubLabelAssessment.Refused
    }
}
