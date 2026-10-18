package com.github.oinsio.gnomish.gitobjects

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.ProcessSupervisor
import com.github.oinsio.gnomish.subprocess.StdinSource
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR17 of fix-terminal-receipt-convergence (design D15 as amended 2026-10-17), the second layer: the
 * plumbing commit chain asserts the frame of every object id git prints it — {@code write-tree}'s
 * tree, {@code commit-tree}'s commit — before handing it to the next step, so a prefix is a loud
 * {@link GitObjectsException} naming the step, and no ref is moved onto it. A real repository for
 * every other step; the one step under test answers through a {@link FakeProcess} (design D16).
 */
class CommitBuilderObjectIdSpec extends Specification implements GitObjectsFixture {

    @TempDir
    Path tempDir

    // Scenario: lifecycle/subprocess-supervision / A value reader asserts its frame
    def "FR17: a #step that exits zero without printing one full object id fails loudly and moves no ref"() {
        given: 'a real repository, and a launch that answers #step with a malformed id'
        def bare = seedBareRepo(tempDir, ['a.txt': 'content'])
        Files.createDirectories(indexDir(tempDir))
        ProcessLaunch real = ProcessLaunch.grouped()
        ProcessLaunch launch = { ProcessBuilder builder, StdinSource stdin ->
            if (!builder.command().contains(step)) {
                return real.start(builder, stdin)
            }
            FakeProcess fake = FakeProcess.running()
            fake.writeStdout(answer)
            fake.exit(0)
            fake
        } as ProcessLaunch
        def exec = new GitExec(bare, 'git', new ProcessSupervisor(), launch)
        def base = ObjectId.of(gitOutput(bare, 'rev-parse', 'refs/heads/base'))

        when:
        new CommitBuilder(exec, indexDir(tempDir)).build(new CommitRequest('refs/heads/gnomish/t1', Optional.empty(), base,
                [
                    new TreeEdit.PutFile('f.txt', 'x'.bytes)
                ], metadata()), null)

        then: 'the failure names the step, never a later one fed with the prefix'
        GitObjectsException e = thrown()
        e.message.contains("git ${step}")
        e.message.contains('object id')

        and: 'no branch was created'
        openGitObjects(bare, tempDir).resolveRef('refs/heads/gnomish/t1').isEmpty()

        where:
        step | answer
        'hash-object' | 'a' * 39 + '\n'
        'write-tree' | 'a' * 39 + '\n'
        'commit-tree' | 'a' * 41 + '\n'
        'commit-tree' | 'not-an-id\n'
    }
}
