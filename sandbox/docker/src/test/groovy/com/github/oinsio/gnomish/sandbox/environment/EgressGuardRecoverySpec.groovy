package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.sandbox.DenialCursor
import com.github.oinsio.gnomish.sandbox.DenialRestoration
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * What a crash does to the guard's denial report and to the guard itself, daemon-free against the
 * recording docker fake. The read position is durable only as the record it rides, so a crash
 * between a read and its record costs a duplicate, never a lost denial, and a denial nobody read
 * before a crash belongs to the round whose read collects it; a guard container that died is
 * started again by the next {@code ensureRunning}, which every box's self-check runs before use.
 *
 * <p>FR1, FR5, FR6 of fix-denial-attribution-durability; NFR-R1 of add-sandbox-core.
 */
class EgressGuardRecoverySpec extends Specification {

    @TempDir
    Path tempDir

    static final String SOURCE = 'sha256:container-1'

    def docker = new RecordingDockerCli()
    def log = []
    boolean running = true

    def setup() {
        docker.onRun = { List<String> args ->
            if (args[0] == 'image') {
                return ok('null')
            }
            if (args == GuardCommands.inspectGuardRunning('k1')) {
                return ok("${running}\n")
            }
            if (args == GuardCommands.inspectGuardId('k1')) {
                return ok(SOURCE + '\n')
            }
            if (args == GuardCommands.startGuard('k1')) {
                running = true
            }
            ok(args[0] == 'logs' ? logsSince(args) : '')
        }
    }

    private EgressGuard guard() {
        new EgressGuard(docker, 'k1', 'mitmproxy/mitmproxy:12', ['registry.example.com'], tempDir.resolve('guard-cfg'),
        new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1'))
    }

    private static DockerResult ok(String stdout) {
        DockerResult.of(0, stdout, '')
    }

    private void deny(String stamp, String host) {
        log << (stamp + ' GNOMISH-EGRESS-DENY {"kind":"connect","host":"' + host + '","port":443}\n')
    }

    /** The daemon's own --since filtering: lines at or after the cursor. */
    private String logsSince(List<String> args) {
        int at = args.indexOf('--since')
        log.findAll {
            at < 0 || !Instant.parse(it.substring(0, it.indexOf(' '))).isBefore(Instant.parse(args[at + 1]))
        }.join('')
    }

    private static List<String> messages(EgressGuard g) {
        g.readDenials().denials()*.finding()*.message()
    }

    // FR5 of fix-denial-attribution-durability: the report follows the read, not the event time
    // Scenario: sandbox/sandbox-egress / A denial is attributed to the round whose read collected it
    def "FR5: a denial left unread by a crash is reported by the read of the round after it"() {
        given: 'round 1 read its denial and committed the position; round 2 was denied, then the factory died before its read'
        deny('2026-08-19T10:00:00.000000000Z', 'first.example.com')
        def before = guard()
        messages(before)
        def committed = before.denialCursor().orElseThrow()
        deny('2026-08-19T10:05:00.000000000Z', 'unread.example.com')

        when: 'the resumed instance restores the committed position and its next round reads'
        def resumed = guard()
        resumed.restoreDenials(DenialRestoration.at(committed))
        def nextRound = messages(resumed)

        then: 'the unread denial is that round\'s, and the one already recorded is not repeated'
        nextRound == [
            'egress denied: unread.example.com:443'
        ]
    }

    // FR6: the position is committed only with the record of the denials it delimits
    // Scenario: sandbox/sandbox-egress / The position never advances ahead of its record
    def "FR6: when the record of a read is lost, the durable position still precedes its denials, so they are read again"() {
        given: 'the last durable record holds a position before the denial'
        deny('2026-08-19T10:00:00.000000000Z', 'first.example.com')
        def first = guard()
        messages(first)
        DenialCursor durable = first.denialCursor().orElseThrow()

        and: 'a read collects a new denial and advances in memory, then the write of its record is lost'
        deny('2026-08-19T10:05:00.000000000Z', 'second.example.com')
        def collected = messages(first)
        assert first.denialCursor().orElseThrow() != durable

        when: 'the next instance starts from what is durable'
        def resumed = guard()
        resumed.restoreDenials(DenialRestoration.at(durable))

        then: 'the denial is read again — a duplicate, never a skip'
        messages(resumed) == collected
        collected == [
            'egress denied: second.example.com:443'
        ]
    }

    // NFR-R1 of add-sandbox-core: the retry's box runs ensureRunning in its self-check, before any probe
    // Scenario: sandbox/sandbox-egress / Guard crash burns no attempt
    def "NFR-R1: a guard container that died is started again by the next ensureRunning"() {
        given: 'the guard crashed: its container exists but no longer runs'
        running = false

        when:
        guard().ensureRunning()

        then: 'it was restarted in place, not recreated'
        docker.runs.contains(GuardCommands.startGuard('k1'))
        running
        !docker.runs.any { it.first() == 'run' }
    }

    // NFR-R1 of add-sandbox-core, D14 of tighten-spec-contract: the round's close probes the guard,
    //     so a guard that died mid-round surfaces as infrastructure. The probe, not the denial read:
    //     the read is fail-open by its own scenario ("An unreadable denial source fails no attempt
    //     and no report"), so the probe is the detector beside it (F85).
    // Scenario: sandbox/sandbox-egress / Guard crash burns no attempt
    def "NFR-R1: the close-of-round probe of a guard that died mid-round reports it unavailable"() {
        given: 'the guard died while the round ran'
        running = false

        when:
        guard().probeRunning()

        then: 'an infrastructure failure naming the guard container'
        def e = thrown(GuardUnavailableException)
        e.message.contains(FactoryDockerLabels.guardName('k1'))

        and: 'D14: the probe does not restart the guard; the next materialize does'
        !docker.runs.contains(GuardCommands.startGuard('k1'))
        !running
    }

    // D14 of tighten-spec-contract: a guard container that is gone altogether is dead too, and the
    //     runtime's own answer rides the message
    def "D14: the probe of a guard whose container is missing reports it unavailable with docker's answer"() {
        given:
        docker.onRun = { List<String> args ->
            args == GuardCommands.inspectGuardRunning('k1') ? DockerResult.of(1, '', 'No such object: gnomish-guard-k1') : ok('')
        }

        when:
        guard().probeRunning()

        then:
        def e = thrown(GuardUnavailableException)
        e.message.contains('missing')
        e.message.contains('No such object')
        docker.runs == [
            GuardCommands.inspectGuardRunning('k1')
        ]
    }

    // D14 of tighten-spec-contract: a live guard passes the probe with one inspect and nothing else
    def "D14: the probe of a running guard passes with a single inspect"() {
        when:
        guard().probeRunning()

        then:
        notThrown(GuardUnavailableException)
        docker.runs == [
            GuardCommands.inspectGuardRunning('k1')
        ]
    }
}
