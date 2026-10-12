package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.BaseRefKind
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome
import com.github.oinsio.gnomish.app.port.git.OriginContact
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR5, NFR-R1 of own-git-transfer-argv: every transfer validates what it receives, with three
 * legacy message ids downgraded to ignore. A base whose history carries a commit git's fsck calls
 * {@code badTimezone} — written by an old tool, nothing else wrong with it — is delivered by the
 * base refresh on real git, not parked.
 */
class LegacyTimezoneBaseRefreshSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    // FR5 of own-git-transfer-argv: badTimezone is one of the three downgraded ids, so the fsck the
    //     refresh enables does not refuse the commit.
    // Scenario: git/git-transfer / A legacy timezone does not refuse a base
    def "FR5: a base whose tip has a malformed timezone is refreshed, not refused"() {
        given: 'origin\'s develop advanced by a commit whose author line carries a two-digit timezone'
        def (Path work, Path origin, Path clone) = initBaseRefTopology(tempDir)
        String parent = gitOutput(origin, 'rev-parse', 'refs/heads/develop')
        String tree = gitOutput(origin, 'rev-parse', 'refs/heads/develop^{tree}')
        Path raw = tempDir.resolve('legacy-commit.txt')
        Files.writeString(raw, "tree ${tree}\nparent ${parent}\n"
                + "author Old Tool <old@tool.example> 1700000000 +05\n"
                + "committer Old Tool <old@tool.example> 1700000000 +05\n\nlegacy timezone\n")
        String legacy = gitOutput(origin, 'hash-object', '-t', 'commit', '-w', '--literally', raw.toString())
        assert gitExitCode(origin, 'update-ref', 'refs/heads/develop', legacy) == 0

        and: 'git itself calls it badTimezone'
        assert gitExitCode(origin, 'fsck', '--no-dangling') != 0

        when:
        def outcome = new BaseRefresh(new GitProcessRunner(), new GitInfrastructureRetry(
                        VirtualTimeEquipment.waitingOn(new VirtualSleeper(new VirtualClock())),
                        GitInfrastructureRetry.DEFAULT_ATTEMPTS, GitInfrastructureRetry.DEFAULT_INITIAL_BACKOFF))
                .refresh(clone, 'develop')

        then: 'the refresh delivers the legacy commit as the base'
        outcome == new BaseRefreshOutcome.Refreshed('develop', legacy, BaseRefKind.BRANCH, OriginContact.CONTACTED)
        gitOutput(clone, 'rev-parse', 'refs/remotes/origin/develop') == legacy
    }
}
