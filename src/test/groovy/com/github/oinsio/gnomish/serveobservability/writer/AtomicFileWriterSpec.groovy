package com.github.oinsio.gnomish.serveobservability.writer

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link AtomicFileWriter#write}: the temp-file-plus-rename primitive behind every
 * observability file write (design D4) — a reader of the target path never
 * observes a partial write (FR1).
 *
 * <p>Implements FR1 of add-serve-observability.
 */
class AtomicFileWriterSpec extends Specification {

    @TempDir
    Path tempDir

    def "writes the full content to a fresh target file"() {
        given:
        def target = tempDir.resolve('snapshot.json')

        when:
        AtomicFileWriter.write(target, '{"a":1}')

        then:
        Files.readString(target) == '{"a":1}'
    }

    def "atomically replaces existing content rather than appending or truncating in place"() {
        given:
        def target = tempDir.resolve('snapshot.json')
        Files.writeString(target, '{"a":"old-and-longer-content"}')

        when:
        AtomicFileWriter.write(target, '{"a":2}')

        then:
        Files.readString(target) == '{"a":2}'
    }

    def "creates the parent directory when it does not yet exist"() {
        given:
        def target = tempDir.resolve('nested').resolve('deeper').resolve('snapshot.json')

        when:
        AtomicFileWriter.write(target, 'content')

        then:
        Files.readString(target) == 'content'
    }

    def "leaves no temp file behind in the target directory after a successful write"() {
        given:
        def target = tempDir.resolve('snapshot.json')

        when:
        AtomicFileWriter.write(target, 'content')

        then:
        def leftovers = tempDir.toFile().listFiles().findAll {
            it.name != 'snapshot.json'
        }
        leftovers.isEmpty()
    }

    // FR1: a target with no parent directory (the filesystem root) is rejected outright,
    // before any filesystem access is attempted.
    def "rejects a target with no parent directory"() {
        when:
        AtomicFileWriter.write(Path.of('/'), 'content')

        then:
        def failure = thrown(IOException)
        failure.message.contains('target has no parent directory')
    }

    // FR1: a failure after the temp file was written (here, an atomic move onto an existing
    // directory) must clean up the temp file and rethrow rather than leave litter behind.
    def "cleans up the temp file and rethrows when the atomic move itself fails"() {
        given:
        def target = tempDir.resolve('snapshot.json')
        Files.createDirectory(target)

        when:
        AtomicFileWriter.write(target, 'content')

        then:
        thrown(IOException)
        def leftovers = tempDir.toFile().listFiles().findAll {
            it.name != 'snapshot.json'
        }
        leftovers.isEmpty()
    }

    // FR1: the core atomicity claim. A background thread hammers the same target with
    // full, self-consistent JSON documents while a foreground reader repeatedly reads
    // the target and parses it; a reader must never observe a truncated or malformed
    // document — only ever one writer's complete "before" or complete "after" content.
    def "concurrent writes never let a reader observe a partial file"() {
        given:
        def target = tempDir.resolve('snapshot.json')
        AtomicFileWriter.write(target, marker(0))
        def writes = 200
        def stop = new AtomicBoolean(false)
        def readerFailure = new AtomicBoolean(false)
        def ready = new CountDownLatch(1)

        def readerPool = Executors.newSingleThreadExecutor()
        def readerTask = readerPool.submit({
            ready.await()
            while (!stop.get()) {
                def text = Files.readString(target)
                if (!isWellFormedMarker(text)) {
                    readerFailure.set(true)
                    break
                }
            }
        } as Runnable)

        when:
        ready.countDown()
        (1..writes).each { i -> AtomicFileWriter.write(target, marker(i)) }
        stop.set(true)
        readerTask.get(10, TimeUnit.SECONDS)
        readerPool.shutdown()

        then:
        !readerFailure.get()
    }

    private static String marker(int i) {
        // A "complete" document has matching open/close markers and an internal
        // length field a reader can cross-check — any interleaving of two writes'
        // bytes breaks one of those invariants.
        def body = "payload-${i}" * 20
        return "<<${body.length()}:${body}>>"
    }

    private static boolean isWellFormedMarker(String text) {
        if (!text.startsWith('<<') || !text.endsWith('>>')) {
            return false
        }
        def inner = text.substring(2, text.length() - 2)
        def colon = inner.indexOf(':')
        if (colon < 0) {
            return false
        }
        def declaredLength = inner.substring(0, colon) as Integer
        def body = inner.substring(colon + 1)
        return body.length() == declaredLength
    }
}
