package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.domain.engine.DenialIdentity
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.sandbox.DenialCursor
import com.github.oinsio.gnomish.sandbox.DenialRead
import com.github.oinsio.gnomish.sandbox.DenialRestoration
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir
/**
 * FR7, NFR-O1, NFR-R1 of add-sandbox-core (design D4): the guard lifecycle —
 * created on the task network with a bridge leg when missing, restarted when
 * stopped, recreated once when broken, {@link GuardUnavailableException} (an
 * infrastructure failure) when nothing brings it up — plus the denial-findings
 * read and the proxy env fragment. Daemon-free against the recording docker
 * fake.
 */
class EgressGuardSpec extends Specification {

    @TempDir
    Path tempDir

    def docker = new RecordingDockerCli()
    static final ObjectOwnership OWNERSHIP = new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1')

    private EgressGuard guard(List<String> allowlist = ['registry.example.com']) {
        new EgressGuard(docker, 'k1', 'mitmproxy/mitmproxy:12', allowlist, tempDir.resolve('guard-cfg'), OWNERSHIP)
    }

    private static DockerResult ok(String stdout = '') {
        new DockerResult(0, stdout, '')
    }

    private static DockerResult failed(String stderr = 'boom') {
        new DockerResult(1, '', stderr)
    }

    def "FR7: a missing guard is created on the task network and connected to the bridge"() {
        given: 'no guard container exists, and every create step succeeds'
        docker.onRun = { List<String> args ->
            args == GuardCommands.inspectGuardRunning('k1') && !docker.runs.contains(GuardCommands.runGuard(
                    'k1', 'mitmproxy/mitmproxy:12', tempDir.resolve('guard-cfg').toAbsolutePath().toString(), OWNERSHIP))
            ? failed('No such object')
            : ok('true\n')
        }

        when:
        guard().ensureRunning()

        then: 'the guard is run with the rendered config and given its bridge leg'
        docker.runs.contains(GuardCommands.runGuard(
                        'k1', 'mitmproxy/mitmproxy:12', tempDir.resolve('guard-cfg').toAbsolutePath().toString(), OWNERSHIP))
        docker.runs.contains(GuardCommands.connectBridge('k1'))

        and: 'the first create sufficed — the recreate repair path never ran'
        !docker.runs.contains(GuardCommands.removeGuard('k1'))

        and: 'the config was rendered before the container started'
        Files.exists(tempDir.resolve('guard-cfg').resolve('guard.py'))
        Files.exists(tempDir.resolve('guard-cfg').resolve('allowlist.json'))
    }

    def "FR7: a running guard is left alone"() {
        given:
        docker.onRun = { List<String> args -> ok('true\n') }

        when:
        guard().ensureRunning()

        then: 'only the state probe ran — no run, start, or remove'
        docker.runs == [
            GuardCommands.inspectGuardRunning('k1')
        ]
    }

    def "NFR-R1: a stopped guard is restarted in place"() {
        given: 'the guard container exists but is stopped, and start brings it up'
        def started = false
        docker.onRun = { List<String> args ->
            if (args == GuardCommands.startGuard('k1')) {
                started = true
                return ok()
            }
            args == GuardCommands.inspectGuardRunning('k1') ? ok(started ? 'true\n' : 'false\n') : ok()
        }

        when:
        guard().ensureRunning()

        then:
        docker.runs.contains(GuardCommands.startGuard('k1'))

        and: 'no recreate was needed'
        !docker.runs.any { it[0] == 'run' }
    }

    def "NFR-R1: a guard that will not start is recreated once"() {
        given: 'the guard exists, start does nothing, and only the recreated container runs'
        def recreated = false
        docker.onRun = { List<String> args ->
            if (args[0] == 'run') {
                recreated = true
                return ok()
            }
            args == GuardCommands.inspectGuardRunning('k1') ? ok(recreated ? 'true\n' : 'false\n') : ok()
        }

        and:
        def logs = LogCaptureSupport.attach(EgressGuard)

        when:
        guard().ensureRunning()

        then: 'the broken guard was removed and a fresh one created with its bridge leg'
        docker.runs.contains(GuardCommands.removeGuard('k1'))
        docker.runs.contains(GuardCommands.connectBridge('k1'))

        and: 'FR15 of harden-logging-observability: a silently recreated guard hides a repeating fault'
        def recreatedWarning = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.EGRESS_GUARD_RECREATED.head())
        }
        recreatedWarning != null
        recreatedWarning.level == Level.WARN
        recreatedWarning.formattedMessage.contains('k1')

        cleanup:
        logs.detach()
    }

    // FR5 of harden-logging-observability: the repair pass verifies its own result, so a refused
    // sub-step is not itself a failure — but when the verification then fails, this DEBUG line is
    // the only record of which step did not take.
    def "FR5: a repair sub-step the daemon refuses leaves a DEBUG trace"() {
        given: 'the guard exists but start is refused; only the recreated container runs'
        def recreated = false
        docker.onRun = { List<String> args ->
            if (args[0] == 'run') {
                recreated = true
                return ok()
            }
            if (args == GuardCommands.startGuard('k1')) {
                return failed('Error response from daemon: no such container')
            }
            args == GuardCommands.inspectGuardRunning('k1') ? ok(recreated ? 'true\n' : 'false\n') : ok()
        }

        when:
        def logged = captureDebug(EgressGuard) { guard().ensureRunning() }

        then: 'the pass still converges by recreating'
        docker.runs.contains(GuardCommands.removeGuard('k1'))

        and: 'and names the step that did not take'
        def traces = logged.findAll {
            it.formattedMessage.contains("repair step 'start'")
        }
        traces.size() == 1
        traces[0].level == Level.DEBUG
        traces[0].formattedMessage.contains('no such container')
    }

    // FR5: the same trace for the other repair sub-step — the removal before a recreate.
    def "FR5: a refused removal before the recreate leaves a DEBUG trace"() {
        given: 'the guard exists, start does nothing, removal is refused, and only the recreate runs'
        def recreated = false
        docker.onRun = { List<String> args ->
            if (args[0] == 'run') {
                recreated = true
                return ok()
            }
            if (args == GuardCommands.removeGuard('k1')) {
                return failed('Error response from daemon: removal already in progress')
            }
            args == GuardCommands.inspectGuardRunning('k1') ? ok(recreated ? 'true\n' : 'false\n') : ok()
        }

        when:
        def logged = captureDebug(EgressGuard) { guard().ensureRunning() }

        then: 'the pass still converges'
        docker.runs.any { it[0] == 'run' }

        and:
        def traces = logged.findAll {
            it.formattedMessage.contains("repair step 'remove'")
        }
        traces.size() == 1
        traces[0].level == Level.DEBUG
        traces[0].formattedMessage.contains('removal already in progress')
    }

    def "NFR-R1: a guard nothing can bring up is an infrastructure failure"() {
        given: 'the guard is never running, whatever is tried'
        docker.onRun = { List<String> args ->
            args == GuardCommands.inspectGuardRunning('k1') ? ok('false\n') : ok()
        }

        when:
        guard().ensureRunning()

        then:
        thrown(GuardUnavailableException)
    }

    def "NFR-R1: a failing docker run of the guard is an infrastructure failure"() {
        given:
        docker.onRun = { List<String> args ->
            args[0] == 'run' ? failed('image not found') : failed('No such object')
        }

        when:
        guard().ensureRunning()

        then:
        def failure = thrown(GuardUnavailableException)
        failure.message.contains('image not found')
    }

    // FR2, UX1, M2 of polish-sandbox-forensics: an operator whose guard will not start pastes the
    // guard container's name into `docker logs` straight from the exception message — asserted at
    // each of the three sites that can throw it, which is what M2 counts.
    def "FR2: every guard-unavailable failure names the guard container ready-to-paste"() {
        given:
        docker.onRun = refuse

        when:
        guard().ensureRunning()

        then:
        def failure = thrown(GuardUnavailableException)
        failure.message.contains('gnomish-guard-k1')

        and: 'NFR-S1: only object names and the runtime answer — no config path, no allowlist entry'
        !failure.message.contains(tempDir.toString())
        !failure.message.contains('registry.example.com')

        where: 'each of the three throw sites'
        site | refuse
        'never comes up' | { List<String> args ->
            args == GuardCommands.inspectGuardRunning('k1') ? new DockerResult(0, 'false\n', '') : new DockerResult(0, '', '')
        }
        'run refused' | { List<String> args ->
            args[0] == 'run' ? new DockerResult(1, '', 'image not found') : new DockerResult(1, '', 'No such object')
        }
        'bridge refused' | { List<String> args ->
            args == GuardCommands.connectBridge('k1')
            ? new DockerResult(1, '', 'network not found')
            : (args == GuardCommands.inspectGuardRunning('k1') ? new DockerResult(1, '', 'No such object') : new DockerResult(0, '', ''))
        }
    }

    def "FR7: an already-connected bridge leg is not an error"() {
        given: 'run succeeds and the bridge connect reports the endpoint already exists'
        docker.onRun = { List<String> args ->
            if (args == GuardCommands.connectBridge('k1')) {
                return failed('endpoint with name gnomish-guard-k1 already exists in network bridge')
            }
            if (args == GuardCommands.inspectGuardRunning('k1')) {
                def probes = docker.runs.count {
                    it == GuardCommands.inspectGuardRunning('k1')
                }
                return probes> 1 ? ok('true\n') : failed('No such object')
            }
            ok()
        }

        when:
        guard().ensureRunning()

        then:
        noExceptionThrown()
    }

    def "NFR-O1: denial findings are parsed from a bounded guard log tail"() {
        given:
        docker.onRun = { List<String> args ->
            args == GuardCommands.guardLogs('k1', 1000, null)
            ? ok(denialLine('2026-08-19T10:00:00.000000000Z', 'evil.example.com'))
            : ok()
        }

        when:
        def findings = guard().readDenials().denials()*.finding()

        then:
        findings*.message() == [
            'egress denied: evil.example.com:443'
        ]
    }

    def "NFR-O1: an unreadable guard log yields no findings, never a failure"() {
        given: 'the guard container is gone'
        docker.onRun = { List<String> args -> failed('No such container') }

        expect:
        guard().readDenials().denials()*.finding() == []
    }

    // D3 of fix-denial-report-attachment: the guard container outlives a lease's rounds, so a
    // round's read must be the delta — an earlier round's denial never lands on a later attempt
    def "D3: two reads around a new denial return it exactly once"() {
        given: 'a daemon whose log grows by one denial between the reads, honoring --since'
        def log = [
            denialLine('2026-08-19T10:00:00.000000000Z', 'first.example.com')
        ]
        docker.onRun = { List<String> args -> ok(logsSince(args, log)) }
        def g = guard()

        when: 'the first round closes and reads'
        def first = g.readDenials().denials()*.finding()

        and: 'a second denial is recorded, then the second round closes and reads'
        log << denialLine('2026-08-19T10:05:00.000000000Z', 'second.example.com')
        def second = g.readDenials().denials()*.finding()

        then: 'each read carries only its own round\'s denial'
        first*.message() == [
            'egress denied: first.example.com:443'
        ]
        second*.message() == [
            'egress denied: second.example.com:443'
        ]

        and: 'the second read asked the daemon for everything past the first read\'s last line'
        lastLogRead() == GuardCommands.guardLogs('k1', 1000, '2026-08-19T10:00:00.000000001Z')
    }

    def "D3: a read with no new denials is empty"() {
        given:
        def log = [
            denialLine('2026-08-19T10:00:00.000000000Z', 'first.example.com')
        ]
        docker.onRun = { List<String> args -> ok(logsSince(args, log)) }
        def g = guard()

        when: 'nothing new happened between the two reads'
        g.readDenials().denials()*.finding()
        def second = g.readDenials().denials()*.finding()

        then: 'the quiet round reports nothing (UX2)'
        second == []
    }

    // D3: an empty window carries no timestamp to advance to, so the cursor must stay put — a
    //     reset would re-read the whole container log and re-attach an earlier round's denials
    def "D3: an empty read keeps the cursor rather than resetting it"() {
        given:
        def log = [
            denialLine('2026-08-19T10:00:00.000000000Z', 'first.example.com')
        ]
        docker.onRun = { List<String> args -> ok(logsSince(args, log)) }
        def g = guard()

        when: 'a first read moves the cursor, then a quiet round reads nothing'
        g.readDenials().denials()*.finding()
        g.readDenials().denials()*.finding()

        and: 'a third round reads again'
        def third = g.readDenials().denials()*.finding()

        then: 'the quiet round left the cursor untouched — the third read still asks past line one'
        lastLogRead() == GuardCommands.guardLogs('k1', 1000, '2026-08-19T10:00:00.000000001Z')

        and: 'so the already-reported denial is not handed out a second time'
        third == []
    }

    // FR5: a resume attaches a NEW guard wrapper to the SURVIVING guard container of a kept
    //     environment, whose log still holds every denial of every earlier round. Those rounds
    //     already committed their own denial lists, so the restored cursor — committed with the
    //     last attempt — is what keeps the resumed round's report free of them.
    def "FR5: a restored cursor keeps a resumed lease from replaying earlier rounds"() {
        given: 'a guard container whose log holds two already-reported rounds'
        def log = [
            denialLine('2026-08-19T10:00:00.000000000Z', 'first.example.com'),
            denialLine('2026-08-19T10:05:00.000000000Z', 'second.example.com')
        ]
        docker.onRun = { List<String> args ->
            guardDaemon(args, log, 'sha256:container-1')
        }

        and: 'the previous instance read them round by round and committed its cursor'
        def before = guard()
        before.readDenials().denials()*.finding()
        before.readDenials().denials()*.finding()
        def committed = before.denialCursor().orElseThrow()

        when: 'the factory restarts, attaches to the same container, and is handed that cursor'
        def afterResume = guard()
        afterResume.restoreDenials(DenialRestoration.at(committed))
        afterResume.ensureRunning()
        def firstRoundAfterResume = afterResume.readDenials().denials()*.finding()

        then: 'the resumed round reports only what happened after the committed position — nothing'
        firstRoundAfterResume == []

        when: 'a denial happens in the resumed round'
        log << denialLine('2026-08-19T10:10:00.000000000Z', 'third.example.com')

        then: 'that one, and only that one, is its own'
        afterResume.readDenials().denials()*.finding()*.message() == [
            'egress denied: third.example.com:443'
        ]
    }

    // M2 of fix-denial-attribution-durability: the failure path is the one with no attempt record
    //     to carry its denials — they were drained onto a cannotExecute escalation, and the
    //     position that drain advanced to rode the park's own commit. A second factory process
    //     resuming over the surviving container must report neither those nor the attempts'.
    def "M2: a resume after a cannotExecute park replays neither the attempts' nor the escalation's denials"() {
        given: 'a guard container whose log holds the denial of a round that closed normally'
        def log = [
            denialLine('2026-08-19T10:00:00.000000000Z', 'first.example.com')
        ]
        docker.onRun = { List<String> args ->
            guardDaemon(args, log, 'sha256:container-1')
        }

        and: 'the first process closed that round, committing its denial and position with the attempt'
        def before = guard()
        before.readDenials().denials()*.finding()

        and: 'then a round denied something and died before its close, and the park drained it'
        log << denialLine('2026-08-19T10:05:00.000000000Z', 'drained.example.com')
        def parked = before.readDenials()

        expect: 'the drain took the failed round\'s denial and the position that delimits it'
        parked.denials()*.finding()*.message() == [
            'egress denied: drained.example.com:443'
        ]
        parked.positionAfter().isPresent()

        when: 'a second factory process attaches to the surviving container with the parked position'
        def resumed = guard()
        resumed.restoreDenials(DenialRestoration.at(parked.positionAfter().orElseThrow()))
        resumed.ensureRunning()
        def firstRoundAfterResume = resumed.readDenials()

        then: 'neither the attempt\'s denial nor the escalation\'s comes back'
        firstRoundAfterResume.denials() == []

        when: 'the resumed round denies something of its own'
        log << denialLine('2026-08-19T10:10:00.000000000Z', 'third.example.com')

        then: 'only that one is reported'
        resumed.readDenials().denials()*.finding()*.message() == [
            'egress denied: third.example.com:443'
        ]
    }

    // FR5: the committed position is a daemon timestamp of the container it was read from. On
    //     another machine — or onto a recreated container — that log is a different one, whose
    //     clock the position does not describe; applying it there could filter out real denials.
    def "FR5: a cursor from another guard container is ignored, not applied"() {
        given: 'a cursor committed against a container this machine does not have'
        def foreign = new DenialCursor('sha256:container-elsewhere', '2026-08-19T10:05:00.000000001Z')

        and: 'a live guard container of its own, with a denial older than that position'
        def log = [
            denialLine('2026-08-19T10:00:00.000000000Z', 'first.example.com')
        ]
        docker.onRun = { List<String> args ->
            guardDaemon(args, log, 'sha256:container-2')
        }

        when:
        def g = guard()
        g.restoreDenials(DenialRestoration.at(foreign))
        def findings = g.readDenials().denials()*.finding()

        then: 'the foreign position is dropped and the local log is read from its start'
        findings*.message() == [
            'egress denied: first.example.com:443'
        ]
        docker.runs.any { it == GuardCommands.guardLogs('k1', 1000, null) }
    }

    def "FR5: the cursor to commit names the container its position was read from"() {
        given:
        def log = [
            denialLine('2026-08-19T10:00:00.000000000Z', 'first.example.com')
        ]
        docker.onRun = { List<String> args ->
            guardDaemon(args, log, 'sha256:container-3')
        }
        def g = guard()

        expect: 'no read yet — no position a later lease could resume from'
        g.denialCursor().isEmpty()

        when:
        g.readDenials().denials()*.finding()

        then: 'the read position is paired with the container identity it belongs to'
        g.denialCursor().orElseThrow() == new DenialCursor('sha256:container-3', '2026-08-19T10:00:00.000000001Z')
    }

    def "FR5: a cursor is not offered when the guard container's identity cannot be read"() {
        given: 'a daemon that serves logs but refuses the identity probe'
        def log = [
            denialLine('2026-08-19T10:00:00.000000000Z', 'first.example.com')
        ]
        docker.onRun = { List<String> args ->
            args == GuardCommands.inspectGuardId('k1') ? failed('No such object') : ok(logsSince(args, log))
        }
        def g = guard()

        when: 'the read hands back its findings and the position that stands after them (D7)'
        def cursor = null
        def logged = captureDebug(GuardSourceIdentity) {
            cursor = g.readDenials().positionAfter()
        }

        then: 'a position with no identifiable source is one a later lease must not apply'
        cursor.isEmpty()

        and: 'FR5 of harden-logging-observability: the lost cursor is traced, not silently dropped'
        logged.any {
            it.level == Level.DEBUG && it.formattedMessage.contains('came back empty')
        }
    }

    def "FR5: the identity of the guard container is probed once and reused"() {
        given:
        def log = [
            denialLine('2026-08-19T10:00:00.000000000Z', 'first.example.com')
        ]
        docker.onRun = { List<String> args ->
            guardDaemon(args, log, 'sha256:container-4')
        }
        def g = guard()

        when: 'several reads and cursor reports happen over the same container'
        g.readDenials().denials()*.finding()
        g.denialCursor()
        g.denialCursor()

        then: 'the identity probe ran once — the id of a live container does not change'
        docker.runs.count { it == GuardCommands.inspectGuardId('k1') } == 1
    }

    // FR5: a recreated container is a DIFFERENT denial source — its log starts empty and its id
    //     differs, so a cursor committed against the old one must not look like it matches
    def "FR5: recreating the guard container re-probes the identity the cursor is matched against"() {
        given: 'a guard that must be recreated once, coming up with a new container id'
        def recreated = false
        def log = [
            denialLine('2026-08-19T10:00:00.000000000Z', 'first.example.com')
        ]
        docker.onRun = { List<String> args ->
            if (args[0] == 'run') {
                recreated = true
                return ok()
            }
            if (args == GuardCommands.inspectGuardRunning('k1')) {
                return ok(recreated ? 'true\n' : 'false\n')
            }
            if (args == GuardCommands.inspectGuardId('k1')) {
                return ok((recreated ? 'sha256:container-new' : 'sha256:container-old') + '\n')
            }
            ok(logsSince(args, log))
        }
        def g = guard()

        and: 'a read against the original container caches its identity'
        g.readDenials().denials()*.finding()
        assert g.denialCursor().orElseThrow().source() == 'sha256:container-old'

        when: 'the guard is recreated and a later round reads again'
        g.ensureRunning()
        g.readDenials().denials()*.finding()

        then: 'the cursor names the container that actually produced the position'
        g.denialCursor().orElseThrow().source() == 'sha256:container-new'
    }

    def "FR5: an offered cursor is dropped, and said to be, when the live identity cannot be read"() {
        given: 'a daemon that serves logs but refuses the identity probe'
        def log = [
            denialLine('2026-08-19T10:00:00.000000000Z', 'first.example.com')
        ]
        docker.onRun = { List<String> args ->
            args == GuardCommands.inspectGuardId('k1') ? failed('No such object') : ok(logsSince(args, log))
        }

        when:
        def g = guard()
        g.restoreDenials(DenialRestoration.at(new DenialCursor('sha256:container-old', '2026-08-19T10:05:00Z')))
        def logged = captureRestore { g.readDenials().denials()*.finding() }

        then: 'the unmatched position is not applied — the log is read from its start'
        docker.runs.any { it == GuardCommands.guardLogs('k1', 1000, null) }

        and: 'and the drop says so rather than passing an unreadable identity off as a mismatch'
        logged.any { it.formattedMessage.contains('(unreadable)') }
    }

    def "FR5: a daemon outage during the identity probe leaves the cursor unreportable, not thrown"() {
        given: 'logs read fine, but the identity probe hits an unreachable daemon'
        def log = [
            denialLine('2026-08-19T10:00:00.000000000Z', 'first.example.com')
        ]
        docker.onRun = { List<String> args ->
            if (args == GuardCommands.inspectGuardId('k1')) {
                throw new DockerUnavailableException('docker daemon is unreachable', null)
            }
            ok(logsSince(args, log))
        }
        def g = guard()

        when: 'the read hands back its findings and the position that stands after them (D7)'
        def cursor = null
        def logged = captureDebug(GuardSourceIdentity) {
            cursor = g.readDenials().positionAfter()
        }

        then: 'no source to pair the position with, and no exception out of an observability read'
        noExceptionThrown()
        cursor.isEmpty()

        and: 'FR5: the outage that cost this attempt its cursor is traced, with its cause'
        def traces = logged.findAll {
            it.level == Level.DEBUG && it.formattedMessage.contains('is unreadable')
        }
        traces.size() == 1
        traces[0].throwableProxy != null
    }

    /**
     * The last {@code docker logs} the guard issued. Since design D7 of
     * fix-denial-attribution-durability a read ends by pairing its findings with the position —
     * which probes the container identity — so the last docker call of a read is the identity
     * probe, not the log read this assertion is about.
     */
    private List<String> lastLogRead() {
        docker.runs.findAll { it.first() == 'logs' }.last()
    }

    /** A daemon that answers the running probe, the identity probe, and --since-filtered logs. */
    private static DockerResult guardDaemon(List<String> args, List<String> log, String containerId) {
        if (args == GuardCommands.inspectGuardRunning('k1')) {
            return ok('true\n')
        }
        if (args == GuardCommands.inspectGuardId('k1')) {
            return ok(containerId + '\n')
        }
        ok(logsSince(args, log))
    }

    // NFR-O1: --tail keeps the newest lines, so a full window means the daemon dropped this
    //     window's older denials — and the cursor advances past them. Silence would report that
    //     permanent loss as a quiet round.
    def "NFR-O1: a read that fills the tail window is warned about"() {
        given: 'the daemon returns exactly as many lines as the tail cap asked for'
        docker.onRun = { List<String> args -> ok(chatter(1000)) }

        when:
        def warnings = capture { guard().readDenials().denials()*.finding() }

        then:
        warnings.any {
            it.level == Level.WARN &&
            it.formattedMessage.startsWith(OperatorEvent.GUARD_DENIAL_TAIL_WINDOW_FULL.head())
        }
    }

    def "NFR-O1: a read below the tail window warns about nothing"() {
        given: 'the daemon returns one line short of the cap'
        docker.onRun = { List<String> args -> ok(chatter(999)) }

        when:
        def warnings = capture { guard().readDenials().denials()*.finding() }

        then:
        warnings.findAll { it.level == Level.WARN }.isEmpty()
    }

    private static String chatter(int count) {
        (0..<count).collect {
            "2026-08-19T10:00:00.00000000${it % 10}Z guard chatter ${it}\n"
        }.join('')
    }

    /** Captures a logger's DEBUG-and-above events through the shared helper (`.claude/rules/logging.md`). */
    private static List<ILoggingEvent> captureDebug(Class<?> owner, Closure emit) {
        def logs = LogCaptureSupport.attach(owner, Level.DEBUG)
        try {
            emit()
            return List.copyOf(logs.list)
        } finally {
            logs.detach()
        }
    }

    /** Captures what consuming a restored position logs — that decision lives in {@link RestoredDenials}. */
    private static List<ILoggingEvent> captureRestore(Closure emit) {
        def logs = LogCaptureSupport.attach(RestoredDenials)
        try {
            emit()
            return List.copyOf(logs.list)
        } finally {
            logs.detach()
        }
    }

    /**
     * Captures what the denial read logs — the reads live in {@link GuardDenialReads}, not the guard.
     * Migrated to the shared helper (task 2.4 of harden-logging-observability) when section 12.6
     * touched this spec.
     */
    private static List<ILoggingEvent> capture(Closure emit) {
        def logs = LogCaptureSupport.attach(GuardDenialReads)
        try {
            emit()
            return List.copyOf(logs.list)
        } finally {
            logs.detach()
        }
    }

    // NFR-R1: a transient docker outage must not silently swallow the denials it could not read
    def "NFR-R1: a failed read neither advances the cursor nor fails"() {
        given: 'a first read that succeeds, then one the daemon refuses, then a third'
        def log = [
            denialLine('2026-08-19T10:00:00.000000000Z', 'first.example.com')
        ]
        def refuse = false
        docker.onRun = { List<String> args ->
            refuse ? failed('daemon gone') : ok(logsSince(args, log))
        }
        def g = guard()

        when:
        g.readDenials().denials()*.finding()
        refuse = true
        def duringOutage = null
        def events = capture {
            duringOutage = g.readDenials().denials()*.finding()
        }

        then: 'the outage is silence to the caller, but not to the log'
        duringOutage == []

        and: 'FR15 of harden-logging-observability: an empty denial list that means "could not read" says so'
        def refusedRead = events.find {
            it.formattedMessage.startsWith(OperatorEvent.GUARD_DENIAL_LOG_READ_FAILED.head())
        }
        refusedRead != null
        refusedRead.level == Level.WARN
        refusedRead.formattedMessage.contains('k1')

        when: 'the daemon recovers and a denial arrived while it was down'
        refuse = false
        log << denialLine('2026-08-19T10:05:00.000000000Z', 'second.example.com')
        def afterOutage = g.readDenials().denials()*.finding()

        then: 'the cursor never moved during the outage, so nothing was lost'
        afterOutage*.message() == [
            'egress denied: second.example.com:443'
        ]
    }

    // NFR-R1: the daemon can be unreachable at round close — DockerCli throws rather than
    // returning a non-ok result there, and a thrown read would discard an already-finished round
    def "NFR-R1: a daemon outage during the read is silence, not a throw"() {
        given: 'a first read that succeeds, then a daemon that is unreachable'
        def log = [
            denialLine('2026-08-19T10:00:00.000000000Z', 'first.example.com')
        ]
        def down = false
        docker.onRun = { List<String> args ->
            if (down) {
                throw new DockerUnavailableException('docker daemon is unreachable', null)
            }
            ok(logsSince(args, log))
        }
        def g = guard()

        when:
        g.readDenials().denials()*.finding()
        down = true
        def duringOutage = null
        def events = capture {
            duringOutage = g.readDenials().denials()*.finding()
        }

        then: 'the outage yields no findings and no exception'
        noExceptionThrown()
        duringOutage == []

        and: 'FR15 of harden-logging-observability: the unreadable log is a coded WARN carrying the fault'
        def unreadable = events.find {
            it.formattedMessage.startsWith(OperatorEvent.GUARD_DENIAL_LOG_UNREADABLE.head())
        }
        unreadable != null
        unreadable.level == Level.WARN
        unreadable.formattedMessage.contains('k1')
        unreadable.throwableProxy != null

        when: 'the daemon comes back and a denial arrived while it was down'
        down = false
        log << denialLine('2026-08-19T10:05:00.000000000Z', 'second.example.com')
        def afterOutage = g.readDenials().denials()*.finding()

        then: 'the cursor never moved during the outage, so nothing was lost'
        afterOutage*.message() == [
            'egress denied: second.example.com:443'
        ]
    }

    private static String denialLine(String stamp, String host) {
        stamp + ' GNOMISH-EGRESS-DENY {"kind":"connect","host":"' + host + '","port":443}\n'
    }

    /** The daemon's own --since filtering: lines strictly at or after the cursor. */
    private static String logsSince(List<String> args, List<String> log) {
        if (args[0] != 'logs') {
            return ''
        }
        int cursorAt = args.indexOf('--since')
        if (cursorAt < 0) {
            return log.join('')
        }
        def cursor = Instant.parse(args[cursorAt + 1])
        log.findAll {
            !Instant.parse(it.substring(0, it.indexOf(' '))).isBefore(cursor)
        }.join('')
    }

    def "FR9: the proxy env fragment names the guard by its stable network alias in both spellings"() {
        expect: 'the alias, not the per-task container name — the address baked image configs dial (9.1, D7)'
        guard().proxyUrl() == 'http://gnomish-guard:8080'
        guard().proxyEnvironment() == [
            HTTP_PROXY : 'http://gnomish-guard:8080',
            HTTPS_PROXY: 'http://gnomish-guard:8080',
            http_proxy : 'http://gnomish-guard:8080',
            https_proxy: 'http://gnomish-guard:8080',
        ]
    }

    // FR7, M2 of fix-denial-attribution-durability: the position is an optimization, the identity
    //     is the correctness. A resume that lost its position but knows what the branch already
    //     records re-reads the whole tail and attaches nothing that is already there.
    def "FR7: a resume that lost its position but kept the identities records no duplicates"() {
        given: 'a guard whose log holds two denials the first process already recorded'
        def log = [
            denialLine('2026-08-19T10:00:00.000000000Z', 'first.example.com'),
            denialLine('2026-08-19T10:05:00.000000000Z', 'second.example.com')
        ]
        docker.onRun = { List<String> args ->
            guardDaemon(args, log, 'sha256:container-1')
        }
        def recorded = guard().readDenials().denials()*.identity() as Set

        when: 'a second process attaches with no position at all, only what the branch records'
        def resumed = guard()
        resumed.restoreDenials(new DenialRestoration(Optional.empty(), recorded))
        def afterResume = resumed.readDenials()

        then: 'the full re-read is merged away entirely — duplicates become a no-op'
        afterResume.denials() == []

        and: 'the read really did go back to the start; nothing was filtered by a position'
        docker.runs.any { it == GuardCommands.guardLogs('k1', 1000, null) }
    }

    // FR7: the merge is reported, so a reviewer can tell a recovered tail from a quiet one
    def "FR7: a merged re-read says how many were already present and how many were recovered"() {
        given: 'a guard log the branch records only the first half of'
        def log = [
            denialLine('2026-08-19T10:00:00.000000000Z', 'first.example.com')
        ]
        docker.onRun = { List<String> args ->
            guardDaemon(args, log, 'sha256:container-1')
        }
        def recorded = guard().readDenials().denials()*.identity() as Set
        log << denialLine('2026-08-19T10:05:00.000000000Z', 'second.example.com')

        when: 'a resume with no position re-reads the whole tail'
        def logs = LogCaptureSupport.attach(RecordedDenialMerge)
        def resumed = guard()
        resumed.restoreDenials(new DenialRestoration(Optional.empty(), recorded))
        def afterResume = resumed.readDenials()

        then: 'only the unrecorded event is attached'
        afterResume.denials()*.finding()*.message() == [
            'egress denied: second.example.com:443'
        ]

        and: 'and the merge outcome is on the record'
        logs.list.any {
            it.level == Level.INFO && it.formattedMessage.contains('1 already present, 1 recovered')
        }

        cleanup:
        logs.detach()
    }

    // FR7: an unstamped denial matches nothing, so it is kept — duplicates over silence (D3)
    def "FR7: a denial with no identity is never merged away"() {
        given: 'a guard whose daemon stamped no line, so nothing can be recognized again'
        def log = [
            'GNOMISH-EGRESS-DENY {"kind":"connect","host":"first.example.com","port":443}\n'
        ]
        docker.onRun = { List<String> args ->
            guardDaemon(args, log, 'sha256:container-1')
        }
        def first = guard().readDenials()

        when:
        def resumed = guard()
        resumed.restoreDenials(new DenialRestoration(Optional.empty(), first.denials()*.identity().findAll() as Set))
        def afterResume = resumed.readDenials()

        then: 'the unidentified denial is reported again rather than silently dropped'
        first.denials()*.identity() == [null]
        afterResume.denials()*.finding()*.message() == [
            'egress denied: first.example.com:443'
        ]
    }

    // FR8, D6: a read that fills its tail window lost older lines of that window permanently —
    //     the report must be able to say "no data" rather than imply "no denials"
    def "FR8: a saturated tail window is reported as a loss marker beside the denials"() {
        given: 'a guard log longer than the tail window the read asks for'
        def log = (1..1000).collect {
            denialLine("2026-08-19T10:00:0${it % 10}.00000000${it % 9}Z", "h${it}.example.com")
        }
        docker.onRun = { List<String> args ->
            guardDaemon(args, log, 'sha256:container-1')
        }

        when:
        def read = guard().readDenials()

        then: 'the loss travels on the findings channel, naming the window it can bound'
        def marker = read.denials()*.finding().find {
            it.message().startsWith('egress denial log truncated')
        }
        marker != null
        marker.message().contains('1000-line window')
        marker.details().contains("the guard container's start")

        and: 'a marker stands for events whose identities are exactly what was lost'
        read.denials().find { it.finding() == marker }.identity() == null
    }

    // FR8: the other visible loss — the source that recorded what the branch holds is gone
    def "FR8: a recorded source that is no longer the live guard is reported as a loss marker"() {
        given: 'a branch recording denials of a guard container this box no longer runs'
        def log = [
            denialLine('2026-08-19T10:00:00.000000000Z', 'first.example.com')
        ]
        docker.onRun = { List<String> args ->
            guardDaemon(args, log, 'sha256:container-live')
        }
        def recorded = [
            new DenialIdentity(
            'sha256:container-gone', '2026-08-19T09:00:00.000000000Z')
        ] as Set

        when:
        def g = guard()
        g.restoreDenials(new DenialRestoration(
                        Optional.of(new DenialCursor('sha256:container-gone', '2026-08-19T09:30:00Z')), recorded))
        def read = g.readDenials()

        then: 'the live guard\'s own denial is read, and the dead source\'s silence is named'
        read.denials()*.finding()*.message().contains('egress denied: first.example.com:443')
        def marker = read.denials()*.finding().find {
            it.message().startsWith('egress denials may be lost')
        }
        marker != null
        marker.details().contains('sha256:container-gone')
        marker.details().contains('sha256:container-live')
    }

    // FR8: "no denials" and "no data" are different answers, and a quiet task gives the first
    def "FR8: a quiet task emits neither a denial nor a loss marker"() {
        given: 'a guard that blocked nothing, resumed with a position of its own live container'
        docker.onRun = { List<String> args ->
            guardDaemon(args, [], 'sha256:container-1')
        }

        when:
        def g = guard()
        g.restoreDenials(DenialRestoration.at(new DenialCursor('sha256:container-1', '2026-08-19T10:00:00Z')))

        then:
        g.readDenials().denials() == []
    }

    // M2, NFR-O2 of fix-denial-attribution-durability: losing BOTH the position and the identities
    //     is the one case that still duplicates — and it must never be the case that goes quiet.
    //     The report repeats, and the reason it repeats is on the record (design D3).
    def "FR4: a resume that lost position and identities alike duplicates, and says why"() {
        given: 'a guard whose log still holds the denial the previous process recorded'
        def log = [
            denialLine('2026-08-19T10:00:00.000000000Z', 'first.example.com')
        ]
        docker.onRun = { List<String> args ->
            guardDaemon(args, log, 'sha256:container-live')
        }
        guard().readDenials()

        when: 'a resume offers a position of a source that is gone, and knows of no recorded denial'
        def resumed = guard()
        DenialRead read = null
        def logged = captureRestore {
            resumed.restoreDenials(DenialRestoration.at(
                    new DenialCursor('sha256:container-gone', '2026-08-19T10:30:00Z')))
            read = resumed.readDenials()
        }

        then: 'the already-recorded denial comes back — a duplicate a reviewer can see, never silence'
        read.denials()*.finding()*.message() == [
            'egress denied: first.example.com:443'
        ]
        docker.runs.any { it == GuardCommands.guardLogs('k1', 1000, null) }

        and: 'and the fallback is explainable rather than mysterious'
        logged.any {
            it.formattedMessage.contains('reading its log from the start (FR5)')
        }
    }
}
