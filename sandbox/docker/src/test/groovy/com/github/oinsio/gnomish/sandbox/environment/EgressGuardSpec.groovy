package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.sandbox.DenialCursor
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.slf4j.LoggerFactory
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

        when:
        guard().ensureRunning()

        then: 'the broken guard was removed and a fresh one created with its bridge leg'
        docker.runs.contains(GuardCommands.removeGuard('k1'))
        docker.runs.contains(GuardCommands.connectBridge('k1'))
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
        def findings = guard().denialFindings()

        then:
        findings*.message() == [
            'egress denied: evil.example.com:443'
        ]
    }

    def "NFR-O1: an unreadable guard log yields no findings, never a failure"() {
        given: 'the guard container is gone'
        docker.onRun = { List<String> args -> failed('No such container') }

        expect:
        guard().denialFindings() == []
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
        def first = g.denialFindings()

        and: 'a second denial is recorded, then the second round closes and reads'
        log << denialLine('2026-08-19T10:05:00.000000000Z', 'second.example.com')
        def second = g.denialFindings()

        then: 'each read carries only its own round\'s denial'
        first*.message() == [
            'egress denied: first.example.com:443'
        ]
        second*.message() == [
            'egress denied: second.example.com:443'
        ]

        and: 'the second read asked the daemon for everything past the first read\'s last line'
        docker.runs.last() == GuardCommands.guardLogs('k1', 1000, '2026-08-19T10:00:00.000000001Z')
    }

    def "D3: a read with no new denials is empty"() {
        given:
        def log = [
            denialLine('2026-08-19T10:00:00.000000000Z', 'first.example.com')
        ]
        docker.onRun = { List<String> args -> ok(logsSince(args, log)) }
        def g = guard()

        when: 'nothing new happened between the two reads'
        g.denialFindings()
        def second = g.denialFindings()

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
        g.denialFindings()
        g.denialFindings()

        and: 'a third round reads again'
        def third = g.denialFindings()

        then: 'the quiet round left the cursor untouched — the third read still asks past line one'
        docker.runs.last() == GuardCommands.guardLogs('k1', 1000, '2026-08-19T10:00:00.000000001Z')

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
        before.denialFindings()
        before.denialFindings()
        def committed = before.denialCursor().orElseThrow()

        when: 'the factory restarts, attaches to the same container, and is handed that cursor'
        def afterResume = guard()
        afterResume.restoreDenialCursor(committed)
        afterResume.ensureRunning()
        def firstRoundAfterResume = afterResume.denialFindings()

        then: 'the resumed round reports only what happened after the committed position — nothing'
        firstRoundAfterResume == []

        when: 'a denial happens in the resumed round'
        log << denialLine('2026-08-19T10:10:00.000000000Z', 'third.example.com')

        then: 'that one, and only that one, is its own'
        afterResume.denialFindings()*.message() == [
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
        g.restoreDenialCursor(foreign)
        def findings = g.denialFindings()

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
        g.denialFindings()

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

        when:
        def cursor = null
        def logged = captureDebug(GuardDenialReads) {
            g.denialFindings()
            cursor = g.denialCursor()
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
        g.denialFindings()
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
        g.denialFindings()
        assert g.denialCursor().orElseThrow().source() == 'sha256:container-old'

        when: 'the guard is recreated and a later round reads again'
        g.ensureRunning()
        g.denialFindings()

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
        g.restoreDenialCursor(new DenialCursor('sha256:container-old', '2026-08-19T10:05:00Z'))
        def logged = capture { g.denialFindings() }

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

        when:
        def cursor = null
        def logged = captureDebug(GuardDenialReads) {
            g.denialFindings()
            cursor = g.denialCursor()
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
        def warnings = capture { guard().denialFindings() }

        then:
        warnings.any {
            it.level == Level.WARN && it.formattedMessage.contains('tail window')
        }
    }

    def "NFR-O1: a read below the tail window warns about nothing"() {
        given: 'the daemon returns one line short of the cap'
        docker.onRun = { List<String> args -> ok(chatter(999)) }

        when:
        def warnings = capture { guard().denialFindings() }

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

    /** Captures what the denial read logs — the reads live in {@link GuardDenialReads}, not the guard. */
    private static List<ILoggingEvent> capture(Closure emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(GuardDenialReads)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
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
        g.denialFindings()
        refuse = true
        def duringOutage = g.denialFindings()

        then: 'the outage is silence, not a throw'
        duringOutage == []

        when: 'the daemon recovers and a denial arrived while it was down'
        refuse = false
        log << denialLine('2026-08-19T10:05:00.000000000Z', 'second.example.com')
        def afterOutage = g.denialFindings()

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
        g.denialFindings()
        down = true
        def duringOutage = g.denialFindings()

        then: 'the outage yields no findings and no exception'
        noExceptionThrown()
        duringOutage == []

        when: 'the daemon comes back and a denial arrived while it was down'
        down = false
        log << denialLine('2026-08-19T10:05:00.000000000Z', 'second.example.com')
        def afterOutage = g.denialFindings()

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
}
