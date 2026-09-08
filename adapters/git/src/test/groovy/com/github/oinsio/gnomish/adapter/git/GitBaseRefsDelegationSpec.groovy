package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome
import com.github.oinsio.gnomish.app.port.git.DefaultBranchDiscovery
import com.github.oinsio.gnomish.app.port.git.ResumeBaseOutcome
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR5, FR6, FR9, FR12 of add-base-ref-resolution: {@link GitBaseRefs#discoverDefaultBranch}, {@link
 * GitBaseRefs#refresh} and {@link GitBaseRefs#resolveForResume} really return what their
 * collaborators ({@link RemoteDefaultBranch}, {@link BaseRefresh}, {@link ResumeBaseResolution})
 * decide — proven here through {@link GitBaseRefs} itself, one minimal scenario per method, rather
 * than assumed from those collaborators' own (much larger) spec suites. {@link
 * GitBaseRefsProbeSpec} covers {@link GitBaseRefs#probe} the same way.
 */
class GitBaseRefsDelegationSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def "discoverDefaultBranch returns the collaborator's own answer"() {
        given:
        def clone = initWorkingRepo(tempDir, 'no-origin')
        commit(clone, 'a.txt', 'seed')

        expect:
        newGitBaseRefs().discoverDefaultBranch(clone) == new DefaultBranchDiscovery.NoRemote()
    }

    def "refresh returns the collaborator's own answer"() {
        given:
        def clone = initWorkingRepo(tempDir, 'no-origin')
        gitOutput(clone, 'checkout', '-b', 'develop')
        commit(clone, 'a.txt', 'seed')

        when:
        def outcome = newGitBaseRefs().refresh(clone, 'develop')

        then:
        outcome instanceof BaseRefreshOutcome.Refused
        (outcome as BaseRefreshOutcome.Refused).report().contains("no 'origin' remote")
    }

    def "resolveForResume returns the collaborator's own answer"() {
        given:
        def clone = initWorkingRepo(tempDir, 'no-origin')
        commit(clone, 'a.txt', 'seed')
        def localTip = gitOutput(clone, 'rev-parse', 'HEAD')

        expect:
        newGitBaseRefs().resolveForResume(clone, 'HEAD') == new ResumeBaseOutcome.Bound('HEAD', localTip)
    }
}
