package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.slf4j.LoggerFactory
import spock.lang.Requires
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
        warnings[0].formattedMessage.startsWith(OperatorEvent.SCRATCH_AREA_ENTRIES_LEFT.head())
        // The count with its own word around it, not a bare '7': the catalog retag (FR14) turned
        // this format string into a concatenation, which made PIT generate a MathMutator on the
        // `count++` behind it — and a bare substring check passes on the mutant's '-7' too.
        warnings[0].formattedMessage.contains('remove 7 entries')
        warnings[0].formattedMessage.contains(root.toString())

        and: 'the first failure rides along with its stack, so the aggregate stays diagnosable'
        warnings[0].throwableProxy != null

        cleanup:
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString('rwxr-xr-x'))
        logs.detach()
    }

    // FR15 of harden-logging-observability: the walk's own failure is a different fault from the
    // per-entry ones — the teardown never even enumerated the tree, so no count exists to report.
    // It leaves a scratch area behind on disk, so it cannot be silent.
    //
    // POSIX permissions aren't meaningful on Windows; this repo targets macOS/Linux (Darwin CI),
    // but the guard keeps the spec portable rather than assuming the platform.
    @Requires({
        !System.getProperty('os.name').toLowerCase().contains('windows')
    })
    def "FR15: a scratch area that cannot even be walked leaves a coded WARN naming it"() {
        given: 'a scratch root the process may not list at all'
        def root = Files.createDirectory(tempDir.resolve('unwalkable'))
        Files.writeString(root.resolve('a.txt'), 'a', StandardCharsets.UTF_8)
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString('-wx------'))
        def logs = LogCaptureSupport.attach(HostChannelFiles)

        when:
        HostChannelFiles.deleteRecursively(root, log)

        then:
        noExceptionThrown()
        def warned = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.SCRATCH_AREA_REMOVAL_INCOMPLETE.head())
        }
        warned != null
        warned.level == Level.WARN
        warned.formattedMessage.contains(root.toString())
        warned.throwableProxy != null

        cleanup:
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString('rwx------'))
        logs.detach()
    }

    // FR15: a channel read that hits the cap hands the caller a silently short answer — the check's
    // findings, the agent's decision file — so the truncation must be on the record with the cap.
    def "FR15: a channel file past the read cap leaves a coded WARN naming the file and the cap"() {
        given: 'a channel file larger than the cap the read is taken with'
        def workingCopy = Files.createDirectory(tempDir.resolve('working-copy'))
        def scratch = Files.createDirectory(tempDir.resolve('scratch'))
        Files.writeString(workingCopy.resolve('findings.json'), 'x' * 64, StandardCharsets.UTF_8)
        def channels = ChannelPathResolver.of(workingCopy, scratch)
        def logs = LogCaptureSupport.attach(HostChannelFiles)

        when:
        def content = HostChannelFiles.readFile(channels, 'findings.json', 8L, log)

        then: 'the caller gets the capped prefix, not a failure'
        content.get().length == 8

        and:
        def warned = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.HOST_CHANNEL_FILE_TRUNCATED.head())
        }
        warned != null
        warned.level == Level.WARN
        warned.formattedMessage.contains('findings.json')
        warned.formattedMessage.contains('8')

        cleanup:
        logs.detach()
    }
}
