package com.github.oinsio.gnomish.testfixtures.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import org.spockframework.runtime.extension.IGlobalExtension
import org.spockframework.runtime.extension.IMethodInterceptor
import org.spockframework.runtime.extension.IMethodInvocation
import org.spockframework.runtime.model.SpecInfo

/**
 * The runtime half of the log contract (FR17, design D16 of harden-logging-observability): a
 * global Spock extension that watches the operator plane of every feature in the run, so an
 * operator line can never be emitted in silence.
 *
 * <p>Why it exists beside the static gate: {@code LogContractGateSpec}'s third check — every
 * catalog code is named by some test source — is satisfied by a code in a comment. This one is
 * behavioral. Together they close the loop: a new degrade path cannot enter the codebase both
 * uncoded (static) and unasserted (here).
 *
 * <p><b>Two outputs, one for each half of the contract.</b> Per feature, every WARN/ERROR no
 * capture was watching is written to a report — always, in every run, because the point is that
 * such a line is never invisible. Per run, the observations are accumulated into a file the
 * {@code checkLogExpectationGate} build task turns into the verdict: a build fails on an operator
 * event <em>code</em> the run emitted and no capture anywhere in the run was watching. See
 * {@link LogExpectationLedger} for why the failure is by code and the report is by feature.
 *
 * <p>The expectation API is deliberately the existing one. A spec declares "I know about this
 * line" by capturing it — through {@link LogCaptureSupport} or through the hand-rolled
 * attach/detach block that predates it, which {@link OperatorLogWatch} reads alike. There is
 * nothing else to learn and nothing to keep in step. The escape hatch is
 * {@link AllowsUnexpectedLogEvents}, for the run whose subject is elsewhere.
 *
 * <p><b>Scope.</b> Registration is a {@code META-INF/services} entry in {@code :test-fixtures}, so
 * the gate reaches exactly the modules that carry it on their test classpath. The four leaf
 * modules that do not — {@code :subprocess}, {@code :atomicfile}, {@code :logtext},
 * {@code :sandbox:core} — put nothing on the operator plane at all: the first two never touch
 * SLF4J, {@code :logtext} hands loggers to its callers without writing through them, and
 * {@code :sandbox:core} logs only at DEBUG. So today the uncovered set and the silent set are the
 * same set, and adding the edge would fail the dependency-analysis gate as an unused dependency.
 * That is a fact about today, not an invariant: a WARN added in one of those modules would be
 * caught by the static gate (which scans every {@code src/main}) but not by this one, and the fix
 * is to give that module the {@code :test-fixtures} edge its first spec-asserted line earns it.
 *
 * <p>A feature that already failed is never judged: the gate observes only when the feature itself
 * passed, so an unexpected WARN can never mask the assertion that explains it.
 */
class LogExpectationGate implements IGlobalExtension {

    /** Where this test task's evidence goes; wired per Test task by {@code test-conventions}. */
    private static final String DIRECTORY_PROPERTY = 'gnomish.logExpectationGate.dir'

    private static final String DEFAULT_DIRECTORY = 'build/reports/log-expectation-gate'

    /** Enough lines to identify the offender without turning a report entry into a log dump. */
    private static final int MAX_REPORTED = 10

    @Override
    void visitSpec(SpecInfo spec) {
        // Per feature, not per spec: `SpecInfo` has no iteration hook of its own, and a
        // spec-wide watch would attribute a leaked WARN to whichever feature happened to be last.
        def interceptor = new FeatureInterceptor()
        spec.allFeatures.each { feature ->
            feature.addIterationInterceptor(interceptor)
        }
    }

    /** Writes the run's evidence where the build task will read it. */
    @Override
    void stop() {
        def file = new File(reportDirectory(), "observations-${ProcessHandle.current().pid()}.tsv")
        file.parentFile?.mkdirs()
        file.text = LogExpectationLedger.observations()
    }

    /**
     * The gate's account of one finished iteration: ledger the codes for the end-of-run verdict,
     * and name every unwatched line in the per-feature report.
     *
     * <p>On the outer class rather than inside the interceptor so a spec can drive the whole
     * decision — the ledger entry, the allowance lookup and the report — without a Spock runner
     * of its own.
     */
    static void observe(IMethodInvocation invocation, OperatorLogEvents events) {
        def location = "${invocation.spec?.name}.${invocation.feature?.name}"
        def allowance = allowance(invocation)
        reasonOf(location, allowance)
        LogExpectationLedger.record(location, events, allowance != null)
        if (allowance == null && !events.unwatched.isEmpty()) {
            report(complaint(location, events.unwatched))
        }
    }

    /**
     * The reason an allowance gives, or {@code null} when there is no allowance. A blank reason
     * fails the feature outright, in every mode: the escape hatch is allowed, an undocumented
     * escape hatch is not — the shape {@code real-time-wiring} and {@code log-contract-exempt} use.
     */
    static String reasonOf(String location, AllowsUnexpectedLogEvents allowance) {
        if (allowance == null) {
            return null
        }
        def reason = allowance.reason()
        assert reason?.trim(): "$location declares @AllowsUnexpectedLogEvents with a blank reason"
        reason
    }

    /** What the per-feature report says about one feature's unwatched operator lines. */
    static String complaint(String location, List<ILoggingEvent> unwatched) {
        def head = "$location emitted ${unwatched.size()} operator log event(s) no capture was " +
                'watching. That is a report, not a failure: the build fails only on a code no ' +
                'capture anywhere in the run was watching. Pin it with LogCaptureSupport, or ' +
                'declare @AllowsUnexpectedLogEvents with a reason.'
        def lines = unwatched.take(MAX_REPORTED).collect { event ->
            "  ${event.level} ${event.loggerName} - ${event.formattedMessage}"
        }
        def tail = unwatched.size() > MAX_REPORTED ? [
            "  ... and ${unwatched.size() - MAX_REPORTED} more"
        ] : []
        ([head] + lines + tail).join('\n')
    }

    /** The allowance covering this invocation: the feature's own, else the spec's or a super spec's. */
    static AllowsUnexpectedLogEvents allowance(IMethodInvocation invocation) {
        def onFeature = invocation.feature?.featureMethod?.reflection?.getAnnotation(AllowsUnexpectedLogEvents)
        if (onFeature != null) {
            return onFeature
        }
        for (SpecInfo spec = invocation.spec?.bottomSpec; spec != null; spec = spec.superSpec) {
            def onSpec = spec.reflection?.getAnnotation(AllowsUnexpectedLogEvents)
            if (onSpec != null) {
                return onSpec
            }
        }
        null
    }

    /** This test task's own report directory, so `:bootstrap`'s three Test tasks do not overwrite. */
    static File reportDirectory() {
        new File(System.getProperty(DIRECTORY_PROPERTY) ?: DEFAULT_DIRECTORY)
    }

    /** The per-feature half: never silent, whatever the verdict turns out to be. */
    private static void report(String complaint) {
        def file = new File(reportDirectory(), "features-${ProcessHandle.current().pid()}.txt")
        file.parentFile?.mkdirs()
        file << complaint + '\n\n'
    }

    /** Watches one iteration — setup, feature and cleanup — and accounts for what it emitted. */
    private static final class FeatureInterceptor implements IMethodInterceptor {

        @Override
        void intercept(IMethodInvocation invocation) throws Throwable {
            def watch = OperatorLogWatch.start()
            boolean failed = false
            try {
                invocation.proceed()
            } catch (Throwable problem) {
                failed = true
                throw problem
            } finally {
                def events = watch.stop()
                if (!failed) {
                    observe(invocation, events)
                }
            }
        }
    }
}
