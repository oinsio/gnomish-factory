package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR14 of add-base-ref-resolution (task 7.3): {@link GitBaseRefs#probe}, the remote outage gate's
 * tracker-free reachability check, delegating verbatim to {@link OriginProbe#answers}. Narrowly
 * scoped to the one method this task adds to {@link GitBaseRefs} — the other three (already
 * covered by {@code BaseRefreshSpec} and friends) are unchanged.
 */
class GitBaseRefsProbeSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def "probe answers true when origin is reachable"() {
        given:
        def work = initWorkingRepo(tempDir, 'work')
        commit(work, 'a.txt', 'one')
        addOrigin(work, tempDir)

        expect:
        newGitBaseRefs().probe(work)
    }

    def "probe answers false when the clone has no origin at all"() {
        given:
        def work = initWorkingRepo(tempDir, 'work')
        commit(work, 'a.txt', 'one')

        expect:
        !newGitBaseRefs().probe(work)
    }
}
