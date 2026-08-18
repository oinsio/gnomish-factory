package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3, NFR-S1 of add-claim-heartbeat — protocol-constant source pinning. The
 * beat interval and TTL multiplier are protocol constants shared by all
 * instances of a project and MUST be read only from the factory's own clone of
 * {@code .gnomish/config.yaml} (the operator's {@code --dir}), loaded once at
 * startup ({@link PipelineStartup#load}). A gnome works in a separate task
 * worktree; anything it writes there — including its own {@code
 * .gnomish/config.yaml} — is never the file the instance's constants came from,
 * so a gnome cannot extend its own TTL or slow its holder's beat for the run.
 *
 * <p>This spec pins the SOURCE and immutability properties only; the parsing
 * and validation of the two keys is covered by {@code TrackerDtoSpec},
 * {@code PipelineMapperHeartbeatSpec}, and {@code TrackerConfigRuleSpec} (task 5.1) and
 * is not re-tested here.
 */
class HeartbeatConstantsSourceSpec extends Specification {

    @TempDir
    Path factoryClone

    @TempDir
    Path gnomeWorktree

    private final PipelineStartup startup = new PipelineStartup(TrackerValidatorStub.acceptingGithubSource())

    /**
     * Writes a complete, valid {@code .gnomish/} tree under {@code root} whose
     * {@code tracker} section pins the two heartbeat protocol constants.
     */
    private static void writeTree(Path root, String interval, int multiplier) {
        GnomishProjectFixture.writeGnomishFile(root, 'config.yaml', """\
schemaVersion: "1"
autonomy:
  attemptLimit: 3
tracker:
  type: github
  heartbeat-interval: ${interval}
  heartbeat-ttl-multiplier: ${multiplier}
  github:
    api-url: https://api.github.com
""")
        GnomishProjectFixture.writePlanStage(root)
    }

    private static RunArguments argsFor(Path dir) {
        new RunArguments(dir, new TaskSource.Inline('t'), null, null,
                RunArguments.InteractiveMode.NONE, RunArguments.Mode.GIT, null, null, false)
    }

    def "FR3/NFR-S1: heartbeat constants are read from the factory clone's --dir/.gnomish, loaded once"() {
        given: 'the factory clone pins a 10-minute beat and a ×4 TTL'
        writeTree(factoryClone, '10m', 4)

        when:
        def outcome = startup.load(argsFor(factoryClone))

        then: 'the loaded TrackerConfig carries exactly the factory clone\'s constants'
        outcome instanceof PipelineLoadOutcome.Loaded
        def tracker = (outcome as PipelineLoadOutcome.Loaded).definition().tracker()
        tracker.heartbeatInterval() == Duration.ofMinutes(10)
        tracker.heartbeatTtlMultiplier() == 4
    }

    def "FR3/NFR-S1: a gnome's worktree config edit does not change the running instance's beat/TTL"() {
        given: 'the factory clone pins 10m/×4 — the protocol constants for the run'
        writeTree(factoryClone, '10m', 4)

        and: 'the instance loads its constants once, from --dir (the factory clone)'
        def loaded = startup.load(argsFor(factoryClone)) as PipelineLoadOutcome.Loaded
        def held = loaded.definition().tracker()

        when: 'a gnome writes a hostile config in ITS worktree mid-round, trying to extend its TTL and slow the beat'
        writeTree(gnomeWorktree, '90m', 50)

        then: 'the held constants are unaffected — the worktree file is never the source'
        held.heartbeatInterval() == Duration.ofMinutes(10)
        held.heartbeatTtlMultiplier() == 4

        and: 'nothing re-reads config mid-run: re-loading the unchanged factory clone still yields 10m/×4'
        def reloaded = (startup.load(argsFor(factoryClone)) as PipelineLoadOutcome.Loaded).definition().tracker()
        reloaded.heartbeatInterval() == Duration.ofMinutes(10)
        reloaded.heartbeatTtlMultiplier() == 4

        and: 'the worktree config IS a genuine, loadable config carrying the gnome\'s hostile values — only the source directory decides, and it is --dir, never the worktree'
        def worktreeTracker = (startup.load(argsFor(gnomeWorktree)) as PipelineLoadOutcome.Loaded).definition().tracker()
        worktreeTracker.heartbeatInterval() == Duration.ofMinutes(90)
        worktreeTracker.heartbeatTtlMultiplier() == 50
    }

    def "FR3/NFR-S1: the loaded TrackerConfig is an immutable snapshot — a later config write cannot mutate the held constants"() {
        given: 'the instance holds the factory clone\'s snapshot'
        writeTree(factoryClone, '10m', 4)
        def held = (startup.load(argsFor(factoryClone)) as PipelineLoadOutcome.Loaded).definition().tracker()
        def capturedInterval = held.heartbeatInterval()
        def capturedMultiplier = held.heartbeatTtlMultiplier()

        when: 'the gnome overwrites its worktree config repeatedly with ever-longer TTLs'
        writeTree(gnomeWorktree, '30m', 9)
        writeTree(gnomeWorktree, '120m', 99)

        then: 'the captured snapshot is unchanged — the record is a value fixed at load time'
        held.heartbeatInterval() == capturedInterval
        held.heartbeatTtlMultiplier() == capturedMultiplier
        held.heartbeatInterval() == Duration.ofMinutes(10)
        held.heartbeatTtlMultiplier() == 4
    }
}
