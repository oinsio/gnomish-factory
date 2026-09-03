package com.github.oinsio.gnomish.logtext

import java.util.concurrent.TimeUnit
import org.slf4j.MDC
import spock.lang.Specification

/**
 * {@link MdcAwareThread}: a helper thread that logs must land its lines in the same task scope the
 * thread that spawned it was in — otherwise the lines describing what a task's process actually
 * said are exactly the ones a {@code grep taskId=} misses.
 *
 * <p>FR8, NFR-O1 of harden-logging-observability — NFR-O1's MDC-completeness contract spec for
 * the thread-hop half (the listener half is {@code MdcEventListenerSpec}).
 */
class MdcAwareThreadSpec extends Specification {

    def cleanup() {
        MDC.clear()
    }

    def "FR8: the spawning thread's context is carried into the new thread"() {
        given:
        MDC.put('taskId', 'T-7')
        MDC.put('stage', 'implement')
        Map<String, String> seen = null

        when:
        def thread = Thread.ofVirtual().unstarted(MdcAwareThread.inheritingContext {
            seen = MDC.getCopyOfContextMap()
        })
        thread.start()
        thread.join(TimeUnit.SECONDS.toMillis(10))

        then:
        seen == [taskId: 'T-7', stage: 'implement']
    }

    def "FR8: the context is captured at wrap time, not at run time"() {
        given: 'the wrap happens while the round\'s context is live'
        MDC.put('taskId', 'T-7')
        Map<String, String> seen = null
        def body = MdcAwareThread.inheritingContext {
            seen = MDC.getCopyOfContextMap()
        }

        and: 'the spawning thread moves on to another task before the helper is ever started'
        MDC.put('taskId', 'T-8')

        when:
        def thread = Thread.ofVirtual().unstarted(body)
        thread.start()
        thread.join(TimeUnit.SECONDS.toMillis(10))

        then: 'the helper logs under the context it was handed, not the one that existed later'
        seen == [taskId: 'T-7']
    }

    def "FR8: the context is cleared when the body returns, so a reused carrier inherits nothing"() {
        given:
        MDC.put('taskId', 'T-7')
        Map<String, String> leaked = null

        when: 'one wrapped body runs, then a bare body runs on the same thread'
        def thread = Thread.ofVirtual().unstarted({
            MdcAwareThread.inheritingContext({
                MDC.put('stage', 'verify')
            }).run()
            leaked = MDC.getCopyOfContextMap()
        })
        thread.start()
        thread.join(TimeUnit.SECONDS.toMillis(10))

        then:
        leaked == null || leaked.isEmpty()
    }

    def "FR8: the context is cleared even when the body throws"() {
        given:
        MDC.put('taskId', 'T-7')
        Map<String, String> leaked = null

        when:
        def thread = Thread.ofVirtual().unstarted({
            try {
                MdcAwareThread.inheritingContext({
                    throw new IllegalStateException('pump died')
                }).run()
            } catch (IllegalStateException ignored) {
                // the point is what the finally left behind, not the exception itself
            }
            leaked = MDC.getCopyOfContextMap()
        })
        thread.start()
        thread.join(TimeUnit.SECONDS.toMillis(10))

        then:
        leaked == null || leaked.isEmpty()
    }

    def "FR8: an empty spawning context is carried faithfully as an empty one"() {
        given: 'no MDC at all — a daemon thread spawning a helper before any task is in hand'
        MDC.clear()
        Map<String, String> seen = [marker: 'untouched']

        when:
        def thread = Thread.ofVirtual().unstarted(MdcAwareThread.inheritingContext {
            seen = MDC.getCopyOfContextMap()
        })
        thread.start()
        thread.join(TimeUnit.SECONDS.toMillis(10))

        then: 'nothing is applied and nothing is invented'
        seen == null || seen.isEmpty()
    }

    def "FR8: the body itself still runs"() {
        given:
        def ran = false

        when:
        MdcAwareThread.inheritingContext({ ran = true }).run()

        then:
        ran
    }

    def "FR8: a daemon body runs under its component key alone"() {
        given: 'the thread that starts the daemon happens to be working a task'
        MDC.put('taskId', 'T-7')
        Map<String, String> seen = null

        when:
        def thread = Thread.ofVirtual().unstarted(MdcAwareThread.asComponent('reaper') {
            seen = MDC.getCopyOfContextMap()
        })
        thread.start()
        thread.join(TimeUnit.SECONDS.toMillis(10))

        then: 'the daemon names itself and inherits nothing — its lines are about the estate, not that task'
        seen == [component: 'reaper']
    }

    def "FR8: the component key is cleared when the daemon loop ends"() {
        given:
        Map<String, String> leaked = null

        when:
        def thread = Thread.ofVirtual().unstarted({
            MdcAwareThread.asComponent('janitor', {}).run()
            leaked = MDC.getCopyOfContextMap()
        })
        thread.start()
        thread.join(TimeUnit.SECONDS.toMillis(10))

        then:
        leaked == null || leaked.isEmpty()
    }

    def "FR8: the daemon body itself still runs"() {
        given:
        def ran = false

        when:
        MdcAwareThread.asComponent('sweep', { ran = true }).run()

        then:
        ran
    }

    def "FR8: the key the log pattern reads is the one the helper writes"() {
        expect:
        MdcAwareThread.COMPONENT_KEY == 'component'
    }
}
