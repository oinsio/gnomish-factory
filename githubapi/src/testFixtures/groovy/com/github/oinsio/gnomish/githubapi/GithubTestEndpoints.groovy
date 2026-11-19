package com.github.oinsio.gnomish.githubapi

import com.github.oinsio.gnomish.domain.egress.AdmittedEndpoint
import com.github.oinsio.gnomish.domain.egress.ConnectionEndpoint
import com.github.oinsio.gnomish.domain.egress.EgressMedium
import com.github.oinsio.gnomish.domain.egress.EndpointDeclaration
import com.github.oinsio.gnomish.httpegress.EgressAdmission
import com.github.oinsio.gnomish.httpegress.OperatorEgress

/**
 * The one way a spec that constructs {@code GithubHttpClient} directly obtains its endpoint (FR17,
 * design D16 of migrate-verify-to-executor-contract, "Test posture"): the spec's WireMock URL, read
 * as the law's inline {@code tracker.github.api-url} and admitted through the production owner,
 * {@link EgressAdmission}, over an operator egress granting {@code loopback} under {@code
 * factory.egress.in-process} — the configuration the fixtures' operator file writes. It is
 * configuration handed to the owner, not a switch: there is no constructor overload and no default
 * that admits a URL unjudged, so a spec whose server is not on loopback is refused like production.
 */
final class GithubTestEndpoints {

    /** The tracker's declaration, as the bundle's factory names it. */
    static final EndpointDeclaration TRACKER = new EndpointDeclaration('tracker.github.api-url', 'api.github.com')

    /** The operator grant the fixtures' operator file writes. */
    static final OperatorEgress LOOPBACK_GRANTED = new OperatorEgress(['loopback'])

    private GithubTestEndpoints() {}

    /**
     * The endpoint a loopback server's URL is admitted as.
     *
     * @param url the server's base URL, on loopback
     * @return the admitted endpoint
     */
    static AdmittedEndpoint loopback(URI url) {
        EgressAdmission.admit(new ConnectionEndpoint(url, EgressMedium.LAW, []), TRACKER, LOOPBACK_GRANTED)
    }
}
