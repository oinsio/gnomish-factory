package com.github.oinsio.gnomish.serveobservability.writer

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.serveobservability.json.LedgerJsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link LedgerAppender}: the shared, synchronized append point behind every
 * ledger line (design D8) — write-only, flush per line, no fsync (design D5).
 *
 * <p>Implements NFR-R2, NFR-R3 of add-serve-observability.
 */
class LedgerAppenderSpec extends Specification implements LifecycleLineFixture {

    @TempDir
    Path tempDir

    private static final ObjectMapper JSON = new ObjectMapper()

    def "appends a single line as valid, newline-terminated JSON"() {
        given:
        def target = tempDir.resolve('ledger-2026-08-03.jsonl')
        def appender = new LedgerAppender(target, new LedgerJsonMapper())

        when:
        appender.append(lifecycleLine('started'))

        then:
        def content = Files.readString(target)
        content.endsWith('\n')
        def lines = content.split('\n')
        lines.length == 1
        JSON.readTree(lines[0]).get('event').asText() == 'started'
    }

    // NFR-R2: a target with no parent directory (the filesystem root) skips the mkdir step
    // (there is no parent to create) and lets the write itself fail with an IOException.
    def "propagates an IOException when the target has no parent directory"() {
        given:
        def appender = new LedgerAppender(Path.of('/'), new LedgerJsonMapper())

        when:
        appender.append(lifecycleLine('started'))

        then:
        thrown(IOException)
    }

    def "creates the parent directory and target file on first append"() {
        given:
        def target = tempDir.resolve('nested').resolve('ledger-2026-08-03.jsonl')
        def appender = new LedgerAppender(target, new LedgerJsonMapper())

        when:
        appender.append(lifecycleLine('started'))

        then:
        Files.exists(target)
    }

    def "appends subsequent lines after existing content rather than truncating"() {
        given:
        def target = tempDir.resolve('ledger-2026-08-03.jsonl')
        def appender = new LedgerAppender(target, new LedgerJsonMapper())

        when:
        appender.append(lifecycleLine('started'))
        appender.append(lifecycleLine('stopped'))

        then:
        def lines = Files.readString(target).split('\n')
        lines.length == 2
        JSON.readTree(lines[0]).get('event').asText() == 'started'
        JSON.readTree(lines[1]).get('event').asText() == 'stopped'
    }

    // NFR-R3: slots finish concurrently and share one appender; the synchronized
    // append+flush per line must serialize writers so no two lines' bytes ever
    // interleave — every line read back must be independently valid JSON.
    def "concurrent appends from many threads never interleave or corrupt a line"() {
        given:
        def target = tempDir.resolve('ledger-2026-08-03.jsonl')
        def appender = new LedgerAppender(target, new LedgerJsonMapper())
        def threadCount = 20
        def appendsPerThread = 50
        ExecutorService pool = Executors.newFixedThreadPool(threadCount)
        def ready = new CountDownLatch(threadCount)
        def go = new CountDownLatch(1)

        when:
        def futures = (0..<threadCount).collect { t ->
            pool.submit({
                ready.countDown()
                go.await()
                (0..<appendsPerThread).each { i ->
                    appender.append(lifecycleLine("t${t}-${i}"))
                }
            } as Runnable)
        }
        ready.await()
        go.countDown()
        futures.each { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        then:
        def lines = Files.readString(target).split('\n')
        lines.length == threadCount * appendsPerThread
        lines.every { line -> isValidJsonObject(line) }
    }

    private static boolean isValidJsonObject(String line) {
        try {
            return JSON.readTree(line).isObject()
        } catch (Exception ignored) {
            return false
        }
    }
}
