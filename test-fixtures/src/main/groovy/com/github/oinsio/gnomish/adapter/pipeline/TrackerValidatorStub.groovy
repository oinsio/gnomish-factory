package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.adapter.agent.claude.ClaudeSettings
import com.github.oinsio.gnomish.app.ConnectionProfiles
import com.github.oinsio.gnomish.app.TrackerSubsectionValidator
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource
import com.github.oinsio.gnomish.domain.egress.EndpointDeclaration
import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors
import com.github.oinsio.gnomish.httpegress.OperatorEgress

/**
 * Test helper: an accept-anything {@link TrackerSubsectionValidator} registry, reproducing the
 * pre-wiring seam behaviour where {@code github} was a registered-but-permissive type. Tests that
 * exercise seam mechanics (unknown type, missing/mismatched subsection) or the {@code take}/{@code
 * run} load path — but NOT GitHub subsection content — pass this so a {@code type: github} config
 * is treated as a known type without demanding real {@code api-url}/{@code repo}/label content.
 *
 * <p>Content validation of the real {@code GithubTrackerSubsectionValidator} through the assembled
 * loader is covered instead by {@code TrackerAdapterConfigurationSpec} (FR17 of add-tracker-port),
 * which lives in {@code adapter.tracker} where importing the concrete validator is permitted.
 *
 * <p>It plays the same role for the check seam (FR6, FR13 of add-plugin-architecture; task 5.2 of
 * migrate-verify-to-executor-contract): {@link #acceptingGithubActions} grades a {@code
 * github-actions} check's settings with nothing, so a fixture whose {@code external} check maps to
 * that built-in loads without the real validator, which lives in {@code :adapters:scm}. No load
 * rule reads the operator's {@code factory.check} configuration, so no fixture configures one.
 *
 * <p>The real sources it builds judge the agent's settings with the Claude dialect's judge, bound as
 * the composition root binds it (FR7 of extract-agent-round-runner).
 */
class TrackerValidatorStub {

    /** A registry whose only entry, {@code github}, accepts any subsection content. */
    static Map<String, TrackerSubsectionValidator> acceptingGithub() {
        [github: { String file, String where, Map subsection ->
                []
            } as TrackerSubsectionValidator]
    }

    /** Settings validators keyed by built-in name: {@code github-actions}, accepting any settings. */
    static Map<String, CheckSettingsValidator> acceptingGithubActions() {
        [(BuiltInExecutors.GITHUB_ACTIONS): { String file, String where, Map settings ->
                []
            } as CheckSettingsValidator]
    }

    /**
     * The real {@code .gnomish/} {@link PipelineSource} over {@link #acceptingGithub}, for the
     * callers that inject the port rather than the registry (task 4.4 of split-into-modules).
     */
    static PipelineSource acceptingGithubSource() {
        new GnomishDirPipelineSource(acceptingGithub(), acceptingGithubActions(), ClaudeSettings.&validate as CheckSettingsValidator,
                loopbackGranted())
    }

    /**
     * The GitHub adapter's endpoint declaration as the bundle's factory makes it ({@code
     * TrackerEndpointRegistrySpec} in {@code :bootstrap} pins the two equal): the fixtures cannot
     * load the bundle, whose jar reaches the test classpath at runtime only.
     */
    static EndpointDeclaration githubEndpoint() {
        new EndpointDeclaration('api-url', 'api.github.com')
    }

    /**
     * What the fixtures' operator file grants (FR17, design D16 of migrate-verify-to-executor-contract,
     * "Test posture"): no connection profile, the GitHub adapter's endpoint declaration, and {@code
     * loopback} under {@code factory.egress.in-process} — the WireMock and Gitea fixtures' URLs are
     * the law's inline {@code api-url} on loopback, admitted by configuration and by no switch.
     */
    static ConnectionAdmission loopbackGranted() {
        new ConnectionAdmission(ConnectionProfiles.none(), [github: githubEndpoint()], new OperatorEgress(['loopback']))
    }

    /** The real {@code .gnomish/} {@link PipelineSource} with no subsection validator registered. */
    static PipelineSource plainSource() {
        new GnomishDirPipelineSource([:], acceptingGithubActions(), ClaudeSettings.&validate as CheckSettingsValidator)
    }
}
