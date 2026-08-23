package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.app.git.ProjectScope
import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory
import org.slf4j.LoggerFactory

/**
 * FR3, NFR-R2, NFR-O1, NFR-C1 of normalize-project-identity-url (design D4, D5): the read side of
 * a sweep pass spans the legacy identity while one exists, at a cost of one extra listing per
 * object kind — and none at all when no legacy identity exists.
 *
 * <p>Companion to {@code SandboxLifecycleSweepSpec}, which drives the same evaluator over a
 * single-identity scope: everything about classification and action is proven there, so this spec
 * asks only what the scope widening changes.
 */
class SandboxLifecycleLegacyScopeSpec extends SandboxLifecycleSweepSpecBase {

    static final String NORMALIZED = 'norm-1'
    static final String LEGACY = 'legacy-1'
    static final def SCOPE = new ProjectScope(NORMALIZED, Optional.of(LEGACY))
    static final def NO_LEGACY = new ProjectScope(NORMALIZED, Optional.empty())
    static final def UNOWNED = new LivenessVerdict.Live([] as Set)

    private static String volumeLine(String key) {
        "gnomish-vol-${key}\tcom.github.oinsio.gnomish.task=${key},com.github.oinsio.gnomish.mode=tracked\n"
    }

    /** Answers every listing empty except the ones the spec scripts by argv. */
    private void listings(Map<List<String>, String> scripted) {
        docker.onRun = { List<String> args ->
            if (existenceProbe(args)) {
                return gone()
            }
            if (scripted.containsKey(args)) {
                return ok(scripted[args])
            }
            if (args == DockerLifecycleCommands.inspectVolumeCreatedAt('gnomish-vol-old')) {
                return ok(OLD.toString())
            }
            if (args == DockerLifecycleCommands.inspectVolumeCreatedAt('gnomish-vol-new')) {
                return ok(OLD.toString())
            }
            ok('')
        }
    }

    private List<ILoggingEvent> evaluate(ProjectScope scope) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(ScopedObjectListing)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            sweep.evaluate(scope, UNOWNED, NOW, THRESHOLDS)
        } finally {
            logbackLogger.detachAppender(appender)
        }
        appender.list
    }

    // FR3: an object stamped before normalization is classified and acted on exactly as one
    //     carrying the current identity — the no-orphan guarantee, at the unit level.
    def "FR3: a legacy-labelled object is listed, classified and acted on"() {
        given: 'the only factory volume on the host carries the pre-normalization label'
        listings(Map.of(
                        DockerLifecycleCommands.listFactoryVolumesWithLabels(LEGACY), volumeLine('old')
                        ))

        when:
        evaluate(SCOPE)

        then: 'it is aged-reaped exactly as a normalized-identity remnant would be'
        verdicts*.category() == [
            SweepVerdictCategory.DISPOSED_AGED
        ]
        1 * disposal.dispose('old')
    }

    // FR3: the per-kind listing runs once per identity in the scope, and the results merge.
    def "FR3: each kind is listed once per identity and the results merge"() {
        given:
        listings(Map.of(
                        DockerLifecycleCommands.listFactoryVolumesWithLabels(NORMALIZED), volumeLine('new'),
                        DockerLifecycleCommands.listFactoryVolumesWithLabels(LEGACY), volumeLine('old')
                        ))

        when:
        evaluate(SCOPE)

        then: 'six listings — three kinds, two identities'
        docker.runs.count {
            it == DockerLifecycleCommands.listFactoryContainersWithLabels(NORMALIZED)
        } == 1
        docker.runs.count {
            it == DockerLifecycleCommands.listFactoryContainersWithLabels(LEGACY)
        } == 1
        docker.runs.count {
            it == DockerLifecycleCommands.listFactoryNetworksWithLabels(LEGACY)
        } == 1

        and: 'both volumes are evaluated, whichever identity they carry'
        verdicts*.objectName().toSorted() == [
            'gnomish-vol-new',
            'gnomish-vol-old'
        ]
    }

    // FR3: an object answering both listings is evaluated once, not twice — merging is by name.
    def "FR3: an object appearing under both identities yields exactly one verdict"() {
        given: 'both listings return the same object name'
        listings(Map.of(
                        DockerLifecycleCommands.listFactoryVolumesWithLabels(NORMALIZED), volumeLine('old'),
                        DockerLifecycleCommands.listFactoryVolumesWithLabels(LEGACY), volumeLine('old')
                        ))

        when:
        evaluate(SCOPE)

        then:
        verdicts*.category() == [
            SweepVerdictCategory.DISPOSED_AGED
        ]
        1 * disposal.dispose('old')
    }

    // NFR-R2 (design D5): fail-closed is per listing, not per scope — a legacy listing that cannot
    //     be obtained aborts the pass, because a silent empty is indistinguishable from "none".
    def "NFR-R2: a failed legacy listing aborts the pass with no verdicts"() {
        given: 'the stamped listing succeeds and finds work; the legacy listing fails'
        docker.onRun = { List<String> args ->
            if (args == DockerLifecycleCommands.listFactoryVolumesWithLabels(LEGACY)) {
                return new DockerResult(1, '', 'Cannot connect to the Docker daemon')
            }
            if (args == DockerLifecycleCommands.listFactoryVolumesWithLabels(NORMALIZED)) {
                return ok(volumeLine('new'))
            }
            ok('')
        }

        when:
        sweep.evaluate(SCOPE, UNOWNED, NOW, THRESHOLDS)

        then:
        thrown(DockerUnavailableException)

        and: 'no verdict escaped before the abort, and nothing was disposed'
        verdicts.isEmpty()
        0 * disposal.dispose(_)
    }

    // NFR-O1: one INFO per pass names how many legacy-labelled objects were found, so the
    //     transition is visible in the log and its count can be watched draining to zero.
    def "NFR-O1: one INFO names the legacy-labelled objects the pass found"() {
        given:
        listings(Map.of(
                        DockerLifecycleCommands.listFactoryVolumesWithLabels(LEGACY), volumeLine('old'),
                        DockerLifecycleCommands.listFactoryNetworksWithLabels(LEGACY),
                        "gnomish-net-old\tcom.github.oinsio.gnomish.task=old,com.github.oinsio.gnomish.mode=tracked\n"
                        ))

        when:
        def events = evaluate(SCOPE)

        then: 'exactly one line, at INFO, tallying both kinds together and naming the legacy identity'
        events.size() == 1
        events[0].level == Level.INFO
        events[0].formattedMessage.contains('2 object(s)')
        events[0].formattedMessage.contains(LEGACY)
    }

    // NFR-O1: a settled installation stays quiet — nothing found means nothing logged.
    def "NFR-O1: no INFO when the legacy listings find nothing"() {
        given:
        listings([:])

        expect:
        evaluate(SCOPE).isEmpty()
    }

    // NFR-C1: with no legacy identity the pass costs exactly what it did before this change.
    def "NFR-C1: a scope without a legacy identity issues no additional listing"() {
        given:
        listings([:])

        when:
        def events = evaluate(NO_LEGACY)

        then: 'three listings, one per kind — and none under any other identity'
        docker.runs.count {
            it.contains('--filter') && it.contains('label=com.github.oinsio.gnomish.project=norm-1')
        } == 3
        docker.runs.every {
            !it.contains('label=com.github.oinsio.gnomish.project=legacy-1')
        }

        and: 'and nothing to report'
        events.isEmpty()
    }
}
