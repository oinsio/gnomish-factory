package com.github.oinsio.gnomish.serveobservability.writer

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.sandboxlifecycle.SkippedVerdictSource
import com.github.oinsio.gnomish.app.sandboxlifecycle.Slf4jSweepVerdictListener
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictFanout
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR9, NFR-O4 of add-serve-sandbox-lifecycle: `take`'s startup pass and the daemon's tick run the
 * same pass, which fans every verdict out to the SLF4J sink and the caller's extra sink — none for
 * `take`, the ledger writer for `serve` ({@code SandboxLifecyclePassFactory}). The same verdict
 * therefore reaches both with one category and one reason. The policy that produces the verdict is
 * one component for every entry point ({@code SandboxLifecycleSweep}); what this spec holds is that
 * the sinks do not translate it apart.
 */
class SweepSinkVocabularySpec extends Specification implements RotatingLedgerAppenderFixture {

    static final Instant NOW = Instant.parse('2026-08-06T09:00:00Z')

    @TempDir
    Path homeDir

    private List<String> sweepLines(Closure emit) {
        def logs = LogCaptureSupport.attach('gnomish.sandbox.lifecycle', Level.DEBUG)
        try {
            emit()
            return logs.list*.formattedMessage
        } finally {
            logs.detach()
        }
    }

    // FR9, NFR-O4: one verdict of an unowned stopped box, through take's sinks and the daemon's
    // F87: scenario text aligned to what this test asserts
    // Scenario: sandbox/sandbox-lifecycle / Same vocabulary in daemon and one-shot logs
    def "FR9: a #category verdict reads the same in take's log and in the daemon's log and ledger"() {
        given: 'the verdict the sweep gives an unowned stopped main box'
        def verdict = new SweepVerdict(category, 'gnomish-box-7', 'main-box', 'tracked', 'task-7',
                'unowned stopped main-box', Duration.ofDays(8),
                category == SweepVerdictCategory.SKIPPED_NO_VERDICT ? SkippedVerdictSource.RUNTIME : null)
        def ledger = new SweepLedgerWriter(ledgerAppenderFor(homeDir.resolve('serve'), NOW),
                new InstanceInfo('gnome-1', 'host1', '1.0.0'), new VirtualClock(NOW))

        when: 'take sinks it to its log; the daemon to its log and its ledger'
        def takeLog = sweepLines {
            new SweepVerdictFanout([
                new Slf4jSweepVerdictListener()
            ]).onVerdict(verdict)
        }
        def daemonLog = sweepLines {
            new SweepVerdictFanout([
                new Slf4jSweepVerdictListener(),
                ledger
            ]).onVerdict(verdict)
        }
        def ledgerLine = Files.readAllLines(ledgerFileFor(homeDir.resolve('serve'), NOW)).find {
            !it.isBlank()
        }

        then: 'the two logs carry the identical line'
        takeLog.size() == 1
        daemonLog == takeLog

        and: 'the ledger carries the same reason, and the same category in its wire spelling'
        takeLog[0].contains("sweep ${category.name()} ")
        takeLog[0].contains('reason="unowned stopped main-box"')
        ledgerLine.contains('"reason":"unowned stopped main-box"')
        ledgerLine.contains("\"category\":\"${wire}\"")

        where:
        category | wire
        SweepVerdictCategory.DISPOSED_AGED | 'disposedAged'
        SweepVerdictCategory.STOPPED_ORPHAN | 'stoppedOrphan'
        SweepVerdictCategory.DISPOSED_RECONSTRUCTIBLE | 'disposedReconstructible'
    }

    // FR9: a verdict that acts on nothing is logged by both entry points alike; the ledger records
    // acting verdicts only (SweepLedgerWriter), so it gets no line.
    // F87: scenario text aligned to what this test asserts
    // Scenario: sandbox/sandbox-lifecycle / Same vocabulary in daemon and one-shot logs
    def "FR9: a kept-under-threshold verdict reads the same in both logs and writes no ledger line"() {
        given: 'the verdict the sweep gives an unowned stopped main box under the age threshold'
        def verdict = new SweepVerdict(SweepVerdictCategory.KEPT_UNDER_THRESHOLD, 'gnomish-box-7', 'main-box', 'tracked',
                'task-7', 'unowned stopped main-box', Duration.ofDays(1), null)
        def ledger = new SweepLedgerWriter(ledgerAppenderFor(homeDir.resolve('serve'), NOW),
                new InstanceInfo('gnome-1', 'host1', '1.0.0'), new VirtualClock(NOW))

        when:
        def takeLog = sweepLines {
            new SweepVerdictFanout([
                new Slf4jSweepVerdictListener()
            ]).onVerdict(verdict)
        }
        def daemonLog = sweepLines {
            new SweepVerdictFanout([
                new Slf4jSweepVerdictListener(),
                ledger
            ]).onVerdict(verdict)
        }

        then:
        takeLog.size() == 1
        daemonLog == takeLog
        def ledgerFile = ledgerFileFor(homeDir.resolve('serve'), NOW)
        !Files.exists(ledgerFile) || Files.readAllLines(ledgerFile).every {
            it.isBlank()
        }
    }
}
