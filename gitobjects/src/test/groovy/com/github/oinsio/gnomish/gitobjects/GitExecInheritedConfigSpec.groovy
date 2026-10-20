package com.github.oinsio.gnomish.gitobjects

import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.ProcessSupervisor
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3 of own-git-invocation-policy (design D2): an inherited per-process configuration does not
 * reach a git the git-object library starts. The vector is the one the test build already plants in
 * every test JVM (the adversarial posture of {@code testing.md}): a {@code GIT_CONFIG_COUNT} pair
 * naming the inert key {@code gnomish.inheritedProbe}, read back by a real git.
 */
class GitExecInheritedConfigSpec extends Specification implements LocalGitRepoFixture {

    static final String PROBE = 'gnomish.inheritedProbe'

    @TempDir
    Path tempDir

    def "FR3: a git the library starts does not see the configuration pair its process inherited"() {
        given: 'a bare repository'
        Path bare = initBareRepo(tempDir)

        and: 'the plant is live: a git started under this JVM\'s environment reads the inherited pair'
        assert gitOutput(bare, 'config', '--get', PROBE) == 'present'

        when: 'the library runs the same read'
        def exec = new GitExec(bare, 'git', new ProcessSupervisor(), ProcessLaunch.grouped(bare))
        def result = exec.run(['config', '--get', PROBE])

        then: 'the key is absent: git reports it unset'
        result.exitCode() == 1
        result.stdoutText().isEmpty()
    }
}
