package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.git.BranchAdoption
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * NFR-R2 of own-git-invocation-policy (design D5): the container resume creates a local branch
 * from a remote-tracking ref without writing the clone's configuration. A pin, not a red-first
 * test: {@link TaskBranchAdoption} already adopts by one {@code update-ref --stdin} transaction,
 * so the pin holds with or without the invocation policy; a return to {@code git branch <n>
 * refs/remotes/origin/<n>} under {@code branch.autoSetupMerge=always} writes a tracking section
 * and fails it.
 *
 * <p>The clone is plain ({@code initPlainWorkingRepo}) with upstream auto-setup the only planted
 * key, and this spec has no shared adversarial setup: the pin isolates the adoption's own form
 * from the policy, which {@code GitInvocationIdentitySpec} covers on the full posture. Real git,
 * no mocking.
 */
class ContainerResumeConfigPinSpec extends Specification implements BareGitRepoFixture {

    static final String TASK = 'PIN-1'
    static final String BRANCH = TaskIdSanitizer.branchName(TASK)

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()

    // Scenario: git/git-invocation-policy / Resuming another instance's branch writes no configuration
    def "NFR-R2: adopting a remote-only branch under forced upstream auto-setup writes no configuration"() {
        given: 'a plain clone with only branch.autoSetupMerge=always, the branch on origin only'
        def clone = initPlainWorkingRepo(tempDir, 'clone')
        commit(clone, 'a.txt', 'seed')
        String tip = gitOutput(clone, 'rev-parse', 'HEAD')
        def origin = initBareRepo(tempDir, 'origin.git')
        addRemote(clone, 'origin', origin.toString())
        FixtureGitConfig.set(clone, 'branch.autoSetupMerge', 'always')
        gitOutput(clone, 'push', 'origin', tip + ':refs/heads/' + BRANCH)
        assert fetchFromOrigin(clone, 'refs/heads/' + BRANCH + ':refs/remotes/origin/' + BRANCH).exitCode() == 0
        assert runner.run(clone, 'rev-parse', '--verify', '--quiet', 'refs/heads/' + BRANCH).exitCode() != 0
        def config = clone.resolve('.git/config').toFile()
        byte[] before = config.bytes

        when:
        def located = new ContainerResumeBranch(runner, { String taskId ->
            Optional.of(new ClaimEpoch(1L))
        } as ClaimEpochSource, VirtualTimeGitRetries.gitInfrastructure()).ensureLocalBranch(clone, TASK)

        then: 'the adoption is reported, the local ref sits at the tracking tip, the config is untouched'
        located == new BranchAdoption.Adopted(TASK)
        gitOutput(clone, 'rev-parse', 'refs/heads/' + BRANCH) == tip
        config.bytes == before
    }
}
