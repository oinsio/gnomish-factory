package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Instant as JavaInstant
import spock.lang.Specification

class SandboxLifecycleObjectReaderSpec extends Specification {

    def docker = new RecordingDockerCli()
    def reader = new SandboxLifecycleObjectReader(docker)

    private static DockerResult ok(String stdout) {
        new DockerResult(0, stdout, '')
    }

    def "list splits name and labels on the tab, tolerating a name with no labels at all"() {
        given:
        docker.onRun = { ok("a\tk=v\nb\n") }

        when:
        def objects = reader.list(ObjectKind.CONTAINER, ['ps'])

        then:
        objects*.name() == ['a', 'b']
        objects*.labels() == [[k: 'v'], [:]]
    }

    // NFR-R1 of add-serve-sandbox-lifecycle: a listing that failed is "cannot enumerate", never
    // "there is nothing" — an empty stdout from a non-zero listing would let the whole pass judge
    // every surviving object as container-less and reap live ones.
    def "list refuses to read a failed listing as an empty one"() {
        given:
        docker.onRun = {
            new DockerResult(1, '', 'Error response from daemon: filter failed')
        }

        when:
        reader.list(ObjectKind.VOLUME, ['volume', 'ls'])

        then:
        def e = thrown(DockerUnavailableException)
        e.message.contains('Error response from daemon: filter failed')
    }

    def "containerTiming returns empty when the inspect call fails"() {
        given:
        docker.onRun = { new DockerResult(1, '', 'no such object') }

        expect:
        reader.containerTiming('n').isEmpty()
    }

    def "containerTiming returns empty when the output does not have exactly four fields"() {
        given:
        docker.onRun = { ok('true 0001-01-01T00:00:00Z') }

        expect:
        reader.containerTiming('n').isEmpty()
    }

    def "containerTiming returns empty when created-at is unparseable"() {
        given:
        docker.onRun = {
            ok('true 0001-01-01T00:00:00Z not-a-date 2026-08-07T09:00:00Z')
        }

        expect:
        reader.containerTiming('n').isEmpty()
    }

    def "containerTiming parses a running container, with finished-at left null"() {
        given:
        docker.onRun = {
            ok('true 0001-01-01T00:00:00Z 2026-08-07T09:00:00Z 2026-08-07T09:00:01Z')
        }

        when:
        def timing = reader.containerTiming('n').get()

        then:
        timing.running()
        timing.createdAt() == Instant('2026-08-07T09:00:00Z')
        timing.startedAt() == Instant('2026-08-07T09:00:01Z')
        timing.finishedAt() == null
    }

    def "containerTiming parses a stopped container, with finished-at populated"() {
        given:
        docker.onRun = {
            ok('false 2026-08-07T10:00:00Z 2026-08-07T09:00:00Z 2026-08-07T09:00:01Z')
        }

        when:
        def timing = reader.containerTiming('n').get()

        then:
        !timing.running()
        timing.finishedAt() == Instant('2026-08-07T10:00:00Z')
    }

    // FR5 of add-serve-sandbox-lifecycle: docker renders "never happened" as the zero time
    // 0001-01-01T00:00:00Z, which parses perfectly well — read literally it makes a created-but-
    // never-started container two millennia old, so the aged reaper disposes box, volume and
    // network the moment the minimum-age guard lets go instead of keeping them for the reap
    // threshold. A field preceding the container's own creation is the sentinel, never a time.
    def "containerTiming reads docker's zero-value timing fields as absent, not as year one"() {
        given:
        docker.onRun = {
            ok('false 0001-01-01T00:00:00Z 2026-08-07T09:00:00Z 0001-01-01T00:00:00Z')
        }

        when:
        def timing = reader.containerTiming('n').get()

        then:
        !timing.running()
        timing.createdAt() == Instant('2026-08-07T09:00:00Z')
        timing.finishedAt() == null
        timing.startedAt() == null
    }

    // An unparseable started-at is already absent — the zero-value guard must pass it through
    // rather than compare it against the creation instant.
    def "containerTiming keeps an unparseable started-at absent"() {
        given:
        docker.onRun = {
            ok('true 0001-01-01T00:00:00Z 2026-08-07T09:00:00Z not-a-date')
        }

        when:
        def timing = reader.containerTiming('n').get()

        then:
        timing.startedAt() == null
    }

    def "createdAt returns empty on an inspect failure, and the parsed instant on success"() {
        given:
        docker.onRun = { List<String> args ->
            args == ['fail'] ? new DockerResult(1, '', 'gone') : ok('2026-08-07T09:00:00Z')
        }

        expect:
        reader.createdAt('n', ['fail']).isEmpty()
        reader.createdAt('n', ['ok']).get() == Instant('2026-08-07T09:00:00Z')
    }

    // A network's created-at is read as `{{json .Created}}`, which marshals Go's time.Time to a
    // QUOTED RFC3339 string — the quotes are the reader's to strip, or the remnant reaches no
    // verdict at all and is never reaped.
    def "createdAt strips the quotes of a json-rendered timestamp"() {
        given:
        docker.onRun = { ok('"2026-08-07T09:00:00.813301176Z"\n') }

        expect:
        reader.createdAt('gnomish-net-k', ['inspect']).get() == Instant('2026-08-07T09:00:00.813301176Z')
    }

    // A daemon whose host clock is not UTC renders RFC3339 with a numeric offset rather than `Z`.
    // That is a real instant, not the unparseable shape above: `Instant.parse` accepts an offset
    // and normalizes it, so such an object must reach a verdict like any other. Asserted because
    // every other timestamp in this spec is `Z`-suffixed, which would keep a UTC-only reader green.
    def "createdAt accepts an offset-rendered timestamp and normalizes it to the same instant"() {
        given:
        def appender = attachAppender()
        docker.onRun = { ok('"2026-08-07T12:00:00.813301176+03:00"\n') }

        when:
        def createdAt = reader.createdAt('gnomish-net-k', ['inspect'])

        then:
        createdAt.get() == Instant('2026-08-07T09:00:00.813301176Z')

        and: 'no verdict was lost — nothing was skipped as unparseable'
        appender.list.findAll {
            it.level.toString() == 'WARN'
        }.isEmpty()

        cleanup:
        detachAppender(appender)
    }

    // The same for the container path, whose four fields go through the same parser: an offset
    // started-at must not be mistaken for the zero-time sentinel by the created-at comparison.
    def "containerTiming accepts offset-rendered timing fields"() {
        given:
        docker.onRun = {
            ok('true 0001-01-01T00:00:00Z 2026-08-07T12:00:00+03:00 2026-08-07T12:00:01+03:00')
        }

        when:
        def timing = reader.containerTiming('gnomish-box-k').get()

        then:
        timing.createdAt() == Instant('2026-08-07T09:00:00Z')
        timing.startedAt() == Instant('2026-08-07T09:00:01Z')
    }

    // An unparseable timestamp costs the object its verdict for the whole pass, so it must never
    // pass silently. The operator-facing half of that is now the sweep's own SKIPPED_NO_VERDICT
    // verdict (FR12 of harden-logging-observability — one owner per fault); what stays here is the
    // raw value the parse choked on, which the verdict does not carry, at DEBUG.
    def "createdAt notes the object name and the raw value when the timestamp is unparseable"() {
        given:
        def appender = attachAppender()
        docker.onRun = { ok('2026-08-07 09:00:01.813301176 +0000 UTC') }

        when:
        def createdAt = reader.createdAt('gnomish-net-k', ['inspect'])

        then:
        createdAt.isEmpty()
        def notes = appender.list.findAll {
            it.level.toString() == 'DEBUG'
        }*.formattedMessage
        notes.size() == 1
        notes[0].contains('gnomish-net-k')
        notes[0].contains('2026-08-07 09:00:01.813301176 +0000 UTC')

        and: 'the console stays clear — the sweep verdict is what reaches the operator'
        appender.list.every { it.level.toString() == 'DEBUG' }

        cleanup:
        detachAppender(appender)
    }

    def "containerTiming notes the container name when its created-at is unparseable"() {
        given:
        def appender = attachAppender()
        docker.onRun = {
            ok('true 0001-01-01T00:00:00Z not-a-date 2026-08-07T09:00:00Z')
        }

        when:
        reader.containerTiming('gnomish-box-k')

        then:
        def notes = appender.list.findAll {
            it.level.toString() == 'DEBUG'
        }*.formattedMessage
        notes.any {
            it.contains('gnomish-box-k') && it.contains('not-a-date')
        }

        cleanup:
        detachAppender(appender)
    }

    /**
     * Migrated to the shared helper (`.claude/rules/logging.md`) when task 5.4 touched this spec —
     * pinned at DEBUG, which is where the reader's per-object notes now live.
     */
    private static LogCaptureSupport attachAppender() {
        LogCaptureSupport.attach(SandboxLifecycleObjectReader, Level.DEBUG)
    }

    private static void detachAppender(LogCaptureSupport logs) {
        logs.detach()
    }

    private static JavaInstant Instant(String s) {
        JavaInstant.parse(s)
    }
}
