package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition.Executor
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck

/**
 * Shared fixture for the {@code ReferencedFiles} spec family: the existence check
 * ({@link ReferencedFilesSpec}, FR6) and the traversal check
 * ({@link ReferencedFilesTraversalSpec}, NFR-S2) drive the same seam and therefore build
 * the same two things — a stage carrying the references under test, and the exact located
 * {@link ConfigError} each miss must produce.
 *
 * <p>These are plain static methods rather than a trait, because both specs call them from
 * {@code where:} blocks, which Spock evaluates outside the specification instance.
 *
 * <p>Implements FR6 and NFR-S2 of load-pipeline-config.
 */
final class ReferencedFilesFixtures {

    private ReferencedFilesFixtures() {
    }

    /** A stage named {@code name} referencing {@code instructionsRef}, with the given verify list. */
    static StageDefinition stage(
            String name, String instructionsRef, List<VerifyCheck> verify = []) {
        new StageDefinition(
                name,
                'purpose',
                [],
                [],
                new Executor(ExecutorType.API, 'model', [:]),
                instructionsRef,
                verify,
                new AutonomyLimits(1),
                AdvancementMode.AUTO)
    }

    /** A judge check whose acceptance criteria live in {@code criteriaFile}. */
    static VerifyCheck.Judge judge(String criteriaFile) {
        new VerifyCheck.Judge(criteriaFile, 'model', [:], 1)
    }

    /** A non-judge check, used to prove that verify indices count every check, not just judges. */
    static VerifyCheck.Command command() {
        new VerifyCheck.Command('true')
    }

    /** The located error for an instructions reference that does not exist. */
    static ConfigError missingInstructions(String stageName, String ref) {
        instructionsError(stageName, "referenced instructions file '${ref}' does not exist")
    }

    /** The located error for a judge criteria reference, at verify index {@code index}, that does not exist. */
    static ConfigError missingCriteria(String stageName, int index, String ref) {
        criteriaError(stageName, index, "referenced acceptance-criteria file '${ref}' does not exist")
    }

    /** The located error for an instructions reference that resolves outside the root (NFR-S2). */
    static ConfigError escapingInstructions(String stageName, String ref) {
        instructionsError(stageName, "referenced instructions file '${ref}' escapes the configuration root")
    }

    /** The located error for a judge criteria reference that resolves outside the root (NFR-S2). */
    static ConfigError escapingCriteria(String stageName, int index, String ref) {
        criteriaError(
                stageName, index, "referenced acceptance-criteria file '${ref}' escapes the configuration root")
    }

    private static ConfigError instructionsError(String stageName, String message) {
        new ConfigError(manifestOf(stageName), 'instructions', message.toString())
    }

    private static ConfigError criteriaError(String stageName, int index, String message) {
        new ConfigError(manifestOf(stageName), "verify[${index}].criteriaFile".toString(), message.toString())
    }

    private static String manifestOf(String stageName) {
        "stages/${stageName}/stage.yaml".toString()
    }
}
