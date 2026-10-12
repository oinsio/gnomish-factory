package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.gittransfer.GitTransfer
import com.github.oinsio.gnomish.gittransfer.TransferSource.SeedPath
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6, FR7 of own-git-transfer-argv (design D6): the seed helper's throwaway global configuration
 * file exists for {@code safe.directory} alone — git honours that key from global scope only — so
 * nothing else the helper configures may land in it. The production script's configuration steps
 * run on real git, in order, with the global file pointed at a temporary path instead of the
 * container's; the clone itself, which needs the container's mounts, is left out.
 */
class DockerSeedGlobalConfigSpec extends Specification {

    @TempDir
    Path tempDir

    // FR6, FR7 of own-git-transfer-argv: every `git config` line of the script runs; only the ones
    //     inside the GIT_CONFIG_GLOBAL export may write global scope, and those write safe.directory.
    // Scenario: git/git-transfer / The seed helper's global file holds only safe.directory
    def "FR7: after the helper's configuration steps the global file holds safe.directory entries and nothing else"() {
        given: "the production script for the owner's seed clone value"
        def owner = GitTransfer.clone(new SeedPath(
                        Path.of(DockerSeedCloneCommand.SEED_SOURCE), Path.of(ContainerTaskExecutionEnvironment.WORKING_COPY)))
        def script = DockerSeedCloneCommand.seedScript(owner)
        Path globalFile = tempDir.resolve('seed-gitconfig')

        and: 'its configuration steps, in order, with the global file redirected; the working copy is a local repository'
        def steps = script.readLines()*.trim().findAll {
            it.startsWith('export GIT_CONFIG_GLOBAL=') || it.startsWith('git config ') || it.startsWith('unset ')
        }.collect {
            it.startsWith('export GIT_CONFIG_GLOBAL=') ? "export GIT_CONFIG_GLOBAL=${globalFile}" : it
        }
        assert steps.count { it.startsWith('git config ') } >= 5
        Path workingCopy = Files.createDirectories(tempDir.resolve('work'))
        assert run(workingCopy, 'git', 'init', '-q') == 0

        when:
        def exit = run(workingCopy, 'sh', '-c', (['set -e'] + steps).join('\n'))

        then: 'every step ran'
        exit == 0

        and: 'the global file holds the two safe.directory entries and nothing else'
        def entries = Files.readAllLines(globalFile)*.trim().findAll {
            it && !it.startsWith('[')
        }
        entries == [
            "directory = ${DockerSeedCloneCommand.SEED_SOURCE}".toString(),
            "directory = ${DockerSeedCloneCommand.SEED_SOURCE}/.git".toString()
        ]
        Files.readString(globalFile).startsWith('[safe]')

        and: "the helper's other settings went to the working copy's own configuration"
        Files.readString(workingCopy.resolve('.git/config')).contains('gnome@sandbox.local')
    }

    private int run(Path directory, String... command) {
        def builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
        TestChildEnvironment.cleared(builder)
        def process = builder.start()
        process.inputStream.text
        assert process.waitFor(30, TimeUnit.SECONDS)
        process.exitValue()
    }
}
