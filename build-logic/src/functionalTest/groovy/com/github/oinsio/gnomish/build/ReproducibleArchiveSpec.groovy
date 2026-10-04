package com.github.oinsio.gnomish.build

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.GZIPInputStream
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Behavioral verification of the reproducible-archive settings of {@code java-conventions}
 * (NFR-R1, design D5 of add-release-pipeline): a miniature project applying the convention and
 * {@code distribution} builds its {@code distTar} twice — the sources' modification times moved
 * in between, as a fresh checkout moves them — and the two archives' SHA-256 sums are compared.
 *
 * <p>The settings are Gradle 9's defaults stated explicitly, so deleting them cannot turn this
 * suite red; the red case is the opt-out a build script can still write,
 * {@code preserveFileTimestamps = true}, which must make the sums differ — proof that the
 * comparison sees timestamps at all. The real archives' identity (M2) is measured by the release
 * workflow's second build, since this build has no Boot plugin to build the boot jar with.
 *
 * <p>Every run is {@code --offline}: the convention's plugins are on the TestKit classpath and
 * an archive task resolves nothing.
 */
class ReproducibleArchiveSpec extends Specification {

    @TempDir
    Path projectDir

    def setup() {
        write('settings.gradle', """\
// The catalog java-conventions reads in the main build.
dependencyResolutionManagement {
    versionCatalogs {
        libs {
            from(files('${GradleRunnerSupport.quotedPath(GradleRunnerSupport.requiredProperty('gnomish.versionCatalog'))}'))
        }
    }
}

rootProject.name = 'mini-archive'
""")
        write('src/main/dist/README.md', 'read me\n')
        write('src/main/dist/share/notes.txt', 'notes\n')
        write('src/launcher/run', '#!/bin/sh\necho run\n')
    }

    def "NFR-R1: two distTar builds of unchanged content have the same SHA-256 sum"() {
        given:
        buildScript('')

        expect:
        archiveSumAfterBuild(Instant.parse('2001-01-01T00:00:00Z')) ==
                archiveSumAfterBuild(Instant.parse('2011-06-15T12:00:00Z'))
    }

    def "NFR-R1: a build that preserves file timestamps is not reproducible — the comparison sees them"() {
        given: 'the one opt-out a build script can still write'
        buildScript('tasks.named(\'distTar\') { preserveFileTimestamps = true }')

        expect:
        archiveSumAfterBuild(Instant.parse('2001-01-01T00:00:00Z')) !=
                archiveSumAfterBuild(Instant.parse('2011-06-15T12:00:00Z'))
    }

    def "NFR-R1: files are 0644 and directories 0755, while a child spec's own 0755 is kept"() {
        given:
        buildScript('')
        archiveSumAfterBuild(Instant.parse('2001-01-01T00:00:00Z'))

        when:
        Map<String, Integer> modes = tarModes(archive())

        then: 'the launcher-like file keeps the mode its copy spec set'
        modes['mini-archive-1.0/bin/run'] == 0755

        and: 'every other file and every directory carries the fixed mode'
        modes['mini-archive-1.0/README.md'] == 0644
        modes['mini-archive-1.0/share/notes.txt'] == 0644
        modes['mini-archive-1.0/share/'] == 0755
        modes['mini-archive-1.0/bin/'] == 0755
    }

    private void buildScript(String extra) {
        write('build.gradle', """\
plugins {
    id 'java-conventions'
    id 'distribution'
}

// After java-conventions, whose placeholder it replaces: a fixed name for the spec to read.
version = '1.0'

distributions {
    main {
        contents {
            from('src/launcher') {
                into 'bin'
                filePermissions { unix('rwxr-xr-x') }
            }
        }
    }
}

tasks.named('distTar', Tar) {
    compression = Compression.GZIP
    archiveExtension = 'tar.gz'
}

${extra}
""")
    }

    /** Moves every source's modification time to {@code mtime}, rebuilds, and hashes the tar. */
    private String archiveSumAfterBuild(Instant mtime) {
        Files.walk(projectDir.resolve('src')).withCloseable { paths ->
            paths.forEach { Files.setLastModifiedTime(it, FileTime.from(mtime)) }
        }
        GradleRunnerSupport.runner(projectDir, 'clean', 'distTar').build()
        MessageDigest.getInstance('SHA-256').digest(Files.readAllBytes(archive())).encodeHex().toString()
    }

    private Path archive() {
        projectDir.resolve('build/distributions/mini-archive-1.0.tar.gz')
    }

    /** Entry name to Unix mode, read straight from the ustar headers. */
    private static Map<String, Integer> tarModes(Path tarGz) {
        byte[] tar = new GZIPInputStream(Files.newInputStream(tarGz)).withCloseable { it.readAllBytes() }
        Map<String, Integer> modes = [:]
        int offset = 0
        while (offset + 512 <= tar.length && tar[offset] != 0) {
            String name = field(tar, offset, 100)
            modes[name] = Integer.parseInt(field(tar, offset + 100, 8), 8) & 07777
            long size = Long.parseLong(field(tar, offset + 124, 12), 8)
            offset += 512 + (int) ((size + 511).intdiv(512) * 512)
        }
        modes
    }

    private static String field(byte[] header, int start, int length) {
        new String(header, start, length, 'US-ASCII').replace('\0', '').trim()
    }

    private void write(String relativePath, String content) {
        GradleRunnerSupport.writeFile(projectDir, relativePath, content)
    }
}
