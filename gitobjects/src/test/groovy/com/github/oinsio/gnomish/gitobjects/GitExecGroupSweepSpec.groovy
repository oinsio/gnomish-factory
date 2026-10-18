package com.github.oinsio.gnomish.gitobjects

import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.ProcessSupervisor
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR21, design D19 of fix-terminal-receipt-convergence: {@link GitExec} sweeps git's process group
 * once its drains are joined, so a descendant git left behind — one that holds none of git's pipes,
 * so no drain is cut — dies with the plumbing call. The descendant is real: a shell alias git runs,
 * which backgrounds a silent {@code sleep} with its output sent away and exits.
 */
class GitExecGroupSweepSpec extends Specification implements GitObjectsFixture {

    @TempDir
    Path tempDir

    // Scenario: lifecycle/subprocess-supervision / A reparented orphan dies with the invocation
    def "FR21: a silent descendant git left behind is gone once the call returns, its result unchanged"() {
        given: 'a bare repository, and an alias that leaves a sleep holding none of git\'s pipes'
        Path bare = seedBareRepo(tempDir, ['file.txt': 'content'])
        Path pidFile = tempDir.resolve('orphan.pid')
        String alias = "alias.leave=!sleep 600 </dev/null >/dev/null 2>&1 & echo \$! > '${pidFile}'"
        def exec = new GitExec(bare, 'git', new ProcessSupervisor(), ProcessLaunch.grouped(tempDir))

        when:
        def result = exec.run(['-c', alias, 'leave'])

        then: 'the alias ran and exited cleanly, its capture whole'
        result.exitCode() == 0
        !result.overflowed()

        and: 'the sleep it left is gone'
        long pid = Long.parseLong(Files.readString(pidFile).trim())
        ProcessHandle.of(pid).ifPresent {
            it.onExit().get(10, TimeUnit.SECONDS)
        }
        !ProcessHandle.of(pid).map { it.isAlive() }.orElse(false)

        cleanup:
        if (Files.exists(pidFile) && !Files.readString(pidFile).isBlank()) {
            ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).ifPresent {
                it.destroyForcibly()
            }
        }
    }
}
