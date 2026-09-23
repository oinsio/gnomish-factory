package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.app.port.git.BranchLocationRefusedException
import java.nio.file.Files
import java.nio.file.Path

/**
 * FR5 of own-git-transfer-argv (design D4): the resume bootstrap is one of the readers that grade
 * a task-branch locate, and a locate git's object validation refused stops it with {@link
 * BranchLocationRefusedException} — never the usage error of a missing branch, never the
 * unavailable arm, and never a worktree materialized over a branch this clone could not fetch.
 *
 * <p>The refusal is produced by a stand-in git that answers everything for real except {@code
 * fetch}, which fails with the stderr git 2.55.0 prints under {@code fetch.fsckObjects=true} — a
 * local bare origin cannot be talked into serving a malformed object on demand. The stderr is the
 * one {@code FetchRefusalSpec} in {@code adapters/git} records; its test sources are not on this
 * module's classpath, so the fixture is restated here.
 */
class GitResumeBootstrapRefusalSpec extends GitResumeSpecBase {

    private static final String OBJECT = '4fac338eb7ab83e161dedf7bd70faca576de5c41'

    private static final String FETCH_REFUSAL = """\
error: object ${OBJECT}: missingEmail: invalid author/committer line - missing email
fatal: fsck error in packed object
fatal: index-pack failed
"""

    def "FR5: bootstrap() stops a resume whose task-branch fetch fails validation, naming the object"() {
        given: 'origin carries the task branch and this clone holds no ref for it, so the locate has to fetch'
        def bare = initBareRepo(tempDir, 'origin.git')
        addRemote(cloneDir, 'origin', bare.toString())
        gitOutput(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')
        gitOutput(cloneDir, 'push', 'origin', 'HEAD:refs/heads/gnomish/PROJ-30')
        gitOutput(cloneDir, 'update-ref', '-d', 'refs/remotes/origin/gnomish/PROJ-30')

        and: 'a git whose every fetch is refused by object validation'
        def git = TaskGitFixture.real(new GitProcessRunner(fetchRefusedGit().toString()))

        when:
        newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out, git).bootstrap(cloneDir, 'PROJ-30')

        then: 'the refusal, with the taskId, the message id and the object git refused'
        def ex = thrown(BranchLocationRefusedException)
        ex.message.contains('PROJ-30')
        ex.message.contains('missingEmail')
        ex.message.contains(OBJECT)

        and: 'no worktree was materialized for a branch the clone could not fetch'
        !Files.exists(expectedWorktree('PROJ-30'))
    }

    /** A git that fails every fetch with git 2.55.0's validation refusal and passes the rest on. */
    private Path fetchRefusedGit() {
        Path stderr = tempDir.resolve('fsck-refusal.stderr')
        stderr.toFile().text = FETCH_REFUSAL
        Path script = tempDir.resolve('fetch-refused-git.sh')
        script.toFile().text = """#!/bin/sh
for a in "\$@"; do
  case "\$a" in
    fetch) cat '${stderr}' 1>&2; exit 128;;
  esac
done
exec git "\$@"
"""
        script.toFile().executable = true
        script
    }
}
