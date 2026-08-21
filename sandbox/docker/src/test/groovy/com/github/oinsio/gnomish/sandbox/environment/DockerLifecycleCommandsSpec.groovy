package com.github.oinsio.gnomish.sandbox.environment

import spock.lang.Specification

/**
 * FR4, FR8 of add-serve-sandbox-lifecycle: the sweep-lifecycle argv builders — project-scoped
 * label listings and the timing/existence inspects — assemble exactly the flags the policy reads
 * the host with, verified without a Docker daemon.
 */
class DockerLifecycleCommandsSpec extends Specification {

    def "sweep-lifecycle: project-scoped label listings for containers, volumes, and networks"() {
        expect:
        DockerLifecycleCommands.listFactoryContainersWithLabels('p1') == [
            'ps',
            '-a',
            '--filter',
            'label=com.github.oinsio.gnomish.factory',
            '--filter',
            'label=com.github.oinsio.gnomish.project=p1',
            '--format',
            '{{.Names}}\t{{.Labels}}'
        ]
        DockerLifecycleCommands.listFactoryVolumesWithLabels('p1') == [
            'volume',
            'ls',
            '--filter',
            'label=com.github.oinsio.gnomish.factory',
            '--filter',
            'label=com.github.oinsio.gnomish.project=p1',
            '--format',
            '{{.Name}}\t{{.Labels}}'
        ]
        DockerLifecycleCommands.listFactoryNetworksWithLabels('p1') == [
            'network',
            'ls',
            '--filter',
            'label=com.github.oinsio.gnomish.factory',
            '--filter',
            'label=com.github.oinsio.gnomish.project=p1',
            '--format',
            '{{.Name}}\t{{.Labels}}'
        ]
    }

    def "sweep-lifecycle: timing inspect commands for containers, volumes, and networks"() {
        expect:
        DockerLifecycleCommands.inspectContainerTiming('n') == [
            'inspect',
            '-f',
            '{{.State.Running}} {{.State.FinishedAt}} {{.Created}} {{.State.StartedAt}}',
            'n'
        ]
        DockerLifecycleCommands.inspectVolumeCreatedAt('n') == [
            'volume',
            'inspect',
            '-f',
            '{{.CreatedAt}}',
            'n'
        ]
        DockerLifecycleCommands.inspectNetworkCreatedAt('n') == [
            'network',
            'inspect',
            '-f',
            '{{json .Created}}',
            'n'
        ]
    }
}
