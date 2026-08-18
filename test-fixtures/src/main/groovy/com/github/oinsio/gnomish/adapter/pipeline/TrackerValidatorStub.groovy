package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.app.CheckParamsValidator
import com.github.oinsio.gnomish.app.TrackerSubsectionValidator
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource

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
 * <p>It plays the same role for the check port (FR6, FR13 of add-plugin-architecture):
 * {@link #discoveredGithubCheckProvider} stands in for a classpath that discovered the github check
 * provider, so a fixture whose {@code external} check resolves to {@code github} loads without the
 * real plugin jar. A spec about provider discovery itself builds its own registry instead.
 */
class TrackerValidatorStub {

    /** A registry whose only entry, {@code github}, accepts any subsection content. */
    static Map<String, TrackerSubsectionValidator> acceptingGithub() {
        [github: { String file, String where, Map subsection ->
                []
            } as TrackerSubsectionValidator]
    }

    /**
     * A check-provider registry standing in for a classpath that discovered only {@code github},
     * grading no params — the keys are the discovered provider set the load seam reads.
     */
    static Map<String, CheckParamsValidator> discoveredGithubCheckProvider() {
        [github: CheckParamsValidator.none()]
    }

    /**
     * The real {@code .gnomish/} {@link PipelineSource} over {@link #acceptingGithub}, for the
     * callers that inject the port rather than the registry (task 4.4 of split-into-modules).
     */
    static PipelineSource acceptingGithubSource() {
        new GnomishDirPipelineSource(acceptingGithub(), discoveredGithubCheckProvider())
    }

    /** The real {@code .gnomish/} {@link PipelineSource} with no subsection validator registered. */
    static PipelineSource plainSource() {
        new GnomishDirPipelineSource([:], discoveredGithubCheckProvider())
    }
}
