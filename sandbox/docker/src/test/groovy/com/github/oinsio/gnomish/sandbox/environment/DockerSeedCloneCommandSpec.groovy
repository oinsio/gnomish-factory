package com.github.oinsio.gnomish.sandbox.environment

import spock.lang.Specification

/**
 * FR3, design D3 of add-sandbox-core; FR2 of add-serve-sandbox-lifecycle: the one-shot seed-clone
 * helper's argv, asserted literally.
 *
 * <p>{@code ContainerTaskExecutionEnvironmentUnitSpec} already asserts that the materializer
 * issues this command — but it does so by comparing against {@code DockerCommands.seedClone(...)}
 * itself, so it cannot see the argv being wrong, only being different from what the materializer
 * asked for. The properties below are the ones a wrong argv would silently cost: the helper is
 * throwaway ({@code --rm}) and anonymous (no {@code --name}, which is exactly why the sweep's role
 * classifier recognizes it by its namelessness, task 3.3), it is network-less, it mounts the
 * factory clone read-only, and it carries the full four-label ownership set like every other
 * factory object.
 */
class DockerSeedCloneCommandSpec extends Specification {

    static final String KEY = 'org-repo-42'
    static final ObjectOwnership OWNERSHIP = new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1')

    def "seedClone is a throwaway, anonymous, network-less helper with the full ownership label set"() {
        when:
        def argv = DockerCommands.seedClone(KEY, 'gnomish/img', '/factory/clone', 'gnomish/task-x', null, OWNERSHIP)

        then: 'throwaway and anonymous: --rm, and no --name for the sweep to classify it by'
        argv[0..1] == ['run', '--rm']
        !argv.contains('--name')

        and: 'all four ownership labels, in the same order every other factory object carries them'
        argv[2..9] == [
            '--label',
            'com.github.oinsio.gnomish.factory=true',
            '--label',
            'com.github.oinsio.gnomish.task=' + KEY,
            '--label',
            'com.github.oinsio.gnomish.mode=tracked',
            '--label',
            'com.github.oinsio.gnomish.project=proj-1'
        ]

        and: 'no network, the factory clone read-only, the task volume at the working copy'
        argv[10..15] == [
            '--network',
            'none',
            '-v',
            '/factory/clone:' + DockerSeedCloneCommand.SEED_SOURCE + ':ro',
            '-v',
            'gnomish-vol-' + KEY + ':' + ContainerTaskExecutionEnvironment.WORKING_COPY
        ]

        and: 'the branch reaches the constant script as a positional parameter, never interpolated'
        argv[16] == 'gnomish/img'
        argv[17..18] == ['sh', '-c']
        !argv[19].contains('gnomish/task-x')
        argv[20..21] == ['gnomish', 'gnomish/task-x']

        and: 'no pin argument when none was chosen'
        argv.size() == 22
    }

    def "an explicit commit pin is appended as the script's second positional parameter"() {
        when:
        def argv = DockerCommands.seedClone(KEY, 'gnomish/img', '/factory/clone', 'gnomish/task-x', 'abc123', OWNERSHIP)

        then:
        argv.size() == 23
        argv[-3..-1] == [
            'gnomish',
            'gnomish/task-x',
            'abc123'
        ]
        !argv[19].contains('abc123')
    }

    def "a manual-mode helper differs from a tracked one in the mode label alone"() {
        given:
        def manual = DockerCommands.seedClone(
                KEY, 'gnomish/img', '/factory/clone', 'gnomish/task-x', null,
                new ObjectOwnership(OwnershipMode.MANUAL, 'proj-1'))

        def tracked = DockerCommands.seedClone(
                KEY, 'gnomish/img', '/factory/clone', 'gnomish/task-x', null, OWNERSHIP)

        expect:
        manual.contains('com.github.oinsio.gnomish.mode=manual')
        tracked.contains('com.github.oinsio.gnomish.mode=tracked')

        and: 'nothing else about the helper changes with the ownership mode'
        manual.findAll { it != 'com.github.oinsio.gnomish.mode=manual' } ==
        tracked.findAll {
            it != 'com.github.oinsio.gnomish.mode=tracked'
        }
    }

    def "the seed script clones with --no-hardlinks and a single branch, guarded by the .git idempotence check"() {
        when:
        def script = DockerCommands.seedClone(
                KEY, 'gnomish/img', '/factory/clone', 'gnomish/task-x', null, OWNERSHIP)[19]

        then: 'a same-filesystem clone must not share object files with the factory repository'
        script.contains('git clone --no-hardlinks --single-branch --branch "$1"')

        and: 're-seeding a volume that already holds the clone changes nothing but an explicit pin'
        script.contains('if [ ! -d ' + ContainerTaskExecutionEnvironment.WORKING_COPY + '/.git ]; then')
        script.contains('if [ -n "${2:-}" ]; then git reset --hard "$2"; fi')

        and: 'the box keeps no remote at all — harvest fetches factory-side'
        script.contains('git remote remove origin')
    }
}
