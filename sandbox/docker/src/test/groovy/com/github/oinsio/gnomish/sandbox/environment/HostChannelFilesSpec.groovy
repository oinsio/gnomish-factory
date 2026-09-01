package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Scratch-area teardown ({@link HostChannelFiles#deleteRecursively}) under D4, FR5 of
 * harden-logging-observability: a tree the factory cannot delete is usually one it cannot delete
 * <em>at all</em> — a permission change, a busy mount — so the failure is reported as one counted
 * line per teardown rather than one line per entry, and a clean teardown reports nothing.
 *
 * <p>Implements FR5, FR12 of harden-logging-observability.
 */
class HostChannelFilesSpec extends Specification {

    @TempDir
    Path tempDir

    def log = LoggerFactory.getLogger(HostChannelFiles)

    def "FR5: a scratch area that removes cleanly says nothing"() {
        given:
        def root = Files.createDirectory(tempDir.resolve('clean'))
        Files.writeString(root.resolve('a.txt'), 'a', StandardCharsets.UTF_8)
        def logs = LogCaptureSupport.attach(HostChannelFiles)

        when:
        HostChannelFiles.deleteRecursively(root, log)

        then:
        !Files.exists(root)
        logs.list.isEmpty()

        cleanup:
        logs.detach()
    }

    def "FR5, D4: many undeletable entries cost one counted line, not one line each"() {
        given: 'a scratch tree whose inner directory the process may read but not write'
        def root = Files.createDirectory(tempDir.resolve('locked'))
        def locked = Files.createDirectory(root.resolve('inner'))
        (1..5).each {
            Files.writeString(locked.resolve("f${it}.txt"), 'x', StandardCharsets.UTF_8)
        }
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString('r-xr-xr-x'))
        def logs = LogCaptureSupport.attach(HostChannelFiles)

        when:
        HostChannelFiles.deleteRecursively(root, log)

        then: 'five files, their non-empty directory and the root above it all failed — in one WARN'
        def warnings = logs.list.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.contains('could not remove 7 entries')
        warnings[0].formattedMessage.contains(root.toString())

        and: 'the first failure rides along with its stack, so the aggregate stays diagnosable'
        warnings[0].throwableProxy != null

        cleanup:
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString('rwxr-xr-x'))
        logs.detach()
    }
}
