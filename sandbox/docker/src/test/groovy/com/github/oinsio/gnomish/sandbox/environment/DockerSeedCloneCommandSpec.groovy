package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.gittransfer.GitTransfer
import com.github.oinsio.gnomish.gittransfer.TransferSource.SeedPath
import java.nio.file.Path
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
    static final String GIT_CONFIG_GLOBAL = 'GIT_CONFIG_GLOBAL'
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

    // FR7, design D6 of own-git-transfer-argv: the identity claim "the seed script's environment
    //     is the owner's" — the clone line is the SeedPath value rendered, with the branch
    //     placeholder as "$1" and the global file the script writes safe.directory into.
    def "the seed script's clone line is the owner's SeedPath value, rendered with the branch as the shell word \$1"() {
        given: "the owner's value for the helper's fixed paths"
        def owner = GitTransfer.clone(new SeedPath(
                        Path.of(DockerSeedCloneCommand.SEED_SOURCE), Path.of(ContainerTaskExecutionEnvironment.WORKING_COPY)))

        when:
        def script = DockerCommands.seedClone(
                KEY, 'gnomish/img', '/factory/clone', 'gnomish/task-x', null, OWNERSHIP)[19]

        then: 'the production script is the rendering of that value, and nothing else'
        script == DockerSeedCloneCommand.seedScript(owner)

        and: 'the clone line, literally: unsets first, then the assignments, then git and the owner\'s argv'
        script.readLines().contains('  env'
                + ' -u GIT_CONFIG_PARAMETERS -u GIT_CONFIG_COUNT -u GIT_OBJECT_DIRECTORY'
                + ' -u GIT_ALTERNATE_OBJECT_DIRECTORIES -u GIT_WORK_TREE -u GIT_INDEX_FILE'
                + ' GIT_ALLOW_PROTOCOL=file GIT_CONFIG_GLOBAL=/tmp/gnomish-seed-gitconfig'
                + ' GIT_CONFIG_SYSTEM=/dev/null XDG_CONFIG_HOME=/dev/null'
                + ' git -c fetch.recurseSubmodules=no -c submodule.recurse=false -c fetch.prune=false'
                + ' -c maintenance.auto=false -c gc.auto=0 -c fetch.fsckObjects=true -c transfer.fsckObjects=true'
                + ' -c fetch.fsck.badTimezone=ignore -c fetch.fsck.missingSpaceBeforeDate=ignore'
                + ' -c fetch.fsck.zeroPaddedFilemode=ignore'
                + ' clone --no-local --no-hardlinks --single-branch --no-tags --no-recurse-submodules'
                + ' --branch "$1" --end-of-options /gnomish/src /gnomish/work')

        and: 'the script writes safe.directory into the very file the owner names as the global scope'
        script.contains("export GIT_CONFIG_GLOBAL=${owner.environment().get('GIT_CONFIG_GLOBAL').get()}\n")
        owner.environment().get('GIT_CONFIG_GLOBAL').get() == SeedPath.SAFE_DIRECTORY_CONFIG
        script.contains('git config --global --add safe.directory ' + DockerSeedCloneCommand.SEED_SOURCE + '\n')
        script.contains('git config --global --add safe.directory ' + DockerSeedCloneCommand.SEED_SOURCE + '/.git\n')

        and: 'the branch is the helper\'s first positional parameter and never part of the literal'
        script.contains('--branch "$1" --end-of-options')
        !script.contains(GitTransfer.BRANCH_PARAMETER)
        !script.contains('gnomish/task-x')

        and: 're-seeding a volume that already holds the clone changes nothing but an explicit pin'
        script.contains('if [ ! -d ' + ContainerTaskExecutionEnvironment.WORKING_COPY + '/.git ]; then')
        script.contains('if [ -n "${2:-}" ]; then git reset --hard "$2"; fi')

        and: 'the box keeps no remote at all — harvest fetches factory-side'
        script.contains('git remote remove origin')
    }

    def "a clone value that names no global file cannot be rendered: safe.directory would have nowhere to go"() {
        given: "the owner's value with its global-file entry #label"
        def owner = GitTransfer.clone(new SeedPath(Path.of('/src'), Path.of('/dest')))
        def environment = new LinkedHashMap<>(owner.environment())
        if (entry == null) {
            environment.remove(GIT_CONFIG_GLOBAL)
        } else {
            environment[GIT_CONFIG_GLOBAL] = entry
        }

        when:
        DockerSeedCloneCommand.seedScript(new GitTransfer(owner.argv(), environment))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('GIT_CONFIG_GLOBAL')

        where:
        label | entry
        'unset' | Optional.<String>empty()
        'missing' | null
    }

    def "a word that would need shell quoting is refused, never quoted: #label"() {
        given: "the owner's value with one element replaced by #label"
        def owner = GitTransfer.clone(new SeedPath(Path.of('/src'), Path.of('/dest')))
        def argv = owner.argv().collect { it == '--no-local' ? argvWord : it }
        def environment = new LinkedHashMap<>(owner.environment())
        environment.putAll(environmentEntries)

        when:
        DockerSeedCloneCommand.seedScript(new GitTransfer(argv, environment))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('shell quoting')
        e.message.contains(word)

        where:
        label | argvWord | environmentEntries | word
        'an argv element with $' | '--x=$HOME' | [:] | '--x=$HOME'
        'an argv element with space'| '--x y' | [:] | '--x y'
        'a value with a quote' | '--no-local' | [GIT_ALLOW_PROTOCOL: Optional.of('fi"le')] | 'fi"le'
        'an unset name with ;' | '--no-local' | ['GIT_X;rm': Optional.empty()] | 'GIT_X;rm'
    }
}
