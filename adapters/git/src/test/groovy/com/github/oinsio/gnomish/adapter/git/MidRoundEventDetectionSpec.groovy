package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.logtext.RepeatSuppressor
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR5 of add-sandbox-core (design D3), FR6 of own-git-transfer-argv: the factory observes the
 * box's tip by its own poll, which runs on the agent's progress events only — no event-driven
 * detection exists, so a gnome commit by itself wakes nothing. A box that counts its harvests is
 * the observation; a gnome commit is the would-be event.
 */
class MidRoundEventDetectionSpec extends Specification implements BareGitRepoFixture {

    static final String BRANCH = 'gnomish/PROJ-9'

    @TempDir
    Path tempDir

    // FR5 of add-sandbox-core.
    // F73: scenario text aligned to what this test asserts — the event-driven scenario was dropped; a commit wakes no poll.
    // Scenario: git/git-task-persistence / Tip observation is a rate-limited factory-side poll
    def "FR5: a gnome commit inside the box, with no agent progress event, wakes no poll and harvests nothing"() {
        given: 'a factory clone with the task branch and a box materialized from it, counting harvests'
        Path clone = initWorkingRepo(tempDir, 'factory-clone')
        commit(clone, 'a.txt', 'first')
        gitOutput(clone, 'branch', BRANCH)
        int harvests = 0
        def box = new LocalBoxEnvironment(clone, Files.createDirectories(tempDir.resolve('box'))) {
                    @Override
                    void harvest() {
                        harvests++
                        super.harvest()
                    }
                }
        box.materialize(BRANCH, null)
        def clock = new VirtualClock(Instant.parse('2026-08-08T10:00:00Z'))
        new MidRoundHarvestListener(box, new HarvestAdmission(new GitProcessRunner(), clone),
                gitOutput(clone, 'rev-parse', 'refs/heads/' + BRANCH), AdmissionRule.midRound(new com.github.oinsio.gnomish.domain.engine.AttemptKey('PROJ-9', 'implement', 0), com.github.oinsio.gnomish.app.port.git.RoundToken.of('a' * 40)), clock, Duration.ofSeconds(30),
                new MidRoundPollContext('PROJ-9', BRANCH, new RepeatSuppressor(clock, RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL)))

        when: 'the gnome commits inside the box and the poll interval elapses, with no agent progress event'
        commit(box.workingCopy, 'work.txt', 'gnome work')
        clock.advance(Duration.ofSeconds(30))

        then: 'only the factory\'s poll observes the tip, and nothing ran it'
        harvests == 0
    }
}
