package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.sandbox.ResourceLimits
import spock.lang.Specification

/**
 * NFR-R2, NFR-C1, M3 of polish-sandbox-forensics: a box kept after a failed self-check is
 * governed by the existing {@code sandbox-lifecycle} sweep and by nothing else — the change adds
 * no sweep logic, no verdict category and no retention rule of its own.
 *
 * <p>What makes that true is that the sweep's universe is defined by labels stamped at
 * <em>creation</em>, not at keep time: the very argv that runs the container carries the factory,
 * task, mode and project labels the listing filters on and the evaluator classifies by. So there
 * is no window in which a box exists outside the sweep's reach, and no state the keep transition
 * can freeze in that the sweep does not already enumerate — the keep only stops a container that
 * was already in the universe.
 */
class KeptSelfCheckBoxSweepUniverseSpec extends Specification {

    static final String KEY = 'org-repo-7-j'
    static final String PROJECT = 'proj-1'
    static final ObjectOwnership OWNERSHIP = new ObjectOwnership(OwnershipMode.TRACKED, PROJECT)
    static final ResourceLimits LIMITS = new ResourceLimits('2', '2g', 512L, '10g')

    /** The labels the adapter's own create command stamps, read back off that argv. */
    private static Map<String, String> labelsStampedAtCreation(List<String> argv) {
        Map<String, String> labels = [:]
        argv.eachWithIndex { arg, i ->
            if (arg == '--label') {
                def (String k, String v) = argv[i + 1].split('=', 2)
                labels[k] = v
            }
        }
        labels
    }

    def "NFR-R2: the box the keep stops was placed in the sweep universe at creation"() {
        given: 'the container the adapter really runs for this environment'
        def argv = DockerCommands.runContainer(KEY, 'gnomish/img', 'runc', LIMITS, false, '/gnomish/work', OWNERSHIP)
        def labels = labelsStampedAtCreation(argv)

        expect: 'its labels satisfy both filters the sweep lists by — it is enumerated, not merely present'
        DockerLifecycleCommands.listFactoryContainersWithLabels(PROJECT)
                .contains(FactoryDockerLabels.factoryLabelFilter())
        labels[FactoryDockerLabels.FACTORY_LABEL] == 'true'
        DockerLifecycleCommands.listFactoryContainersWithLabels(PROJECT)
                .contains(FactoryDockerLabels.projectLabelFilter(PROJECT))
        labels[FactoryDockerLabels.PROJECT_LABEL] == PROJECT
    }

    def "NFR-C1: the kept box classifies with its own role and key, so the existing matrix bounds it"() {
        given: 'the same container, as the sweep listing would report it after the keep stopped it'
        def argv = DockerCommands.runContainer(KEY, 'gnomish/img', 'runc', LIMITS, false, '/gnomish/work', OWNERSHIP)
        def listed = new ListedDockerObject(
                FactoryDockerLabels.containerName(KEY), ObjectKind.CONTAINER, labelsStampedAtCreation(argv))

        when:
        def classification = SandboxLifecycleClassification.of(listed)

        then: 'the evaluator recovers everything its decision matrix needs from the object itself'
        classification != null
        classification.environmentKey() == KEY
        classification.baseTaskKey() == 'org-repo-7'
        classification.role() == ObjectRole.JUDGE
        classification.mode() == OwnershipMode.TRACKED
    }

    def "the keep itself changes no label — stopping is all it does"() {
        expect: 'the stop argv names the container and carries nothing else'
        DockerCommands.stop(FactoryDockerLabels.containerName(KEY)) == ['stop', 'gnomish-box-' + KEY]
    }
}
