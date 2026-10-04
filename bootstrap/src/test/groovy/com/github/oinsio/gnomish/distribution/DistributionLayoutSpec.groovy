package com.github.oinsio.gnomish.distribution

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import spock.lang.Shared
import spock.lang.Specification

/**
 * The operator archives as this build produced them (FR4 of add-release-pipeline, design D4):
 * their names, the folder they unpack to, and every file in them against the layout — read from
 * the real {@code distTar}/{@code distZip} outputs, whose paths {@code verification.gradle} hands
 * over as {@code e2e.distTarPath} / {@code e2e.distZipPath}.
 *
 * <p>The expected {@code share/} content is derived from {@code docs/examples/} on disk, so a new
 * example needs no edit here; what the spec fixes is the mapping — the sandbox-image recipe under
 * {@code share/sandbox-image/} and nowhere else.
 */
class DistributionLayoutSpec extends Specification {

    private static final String RECIPE = 'docs/examples/sandbox-image'

    @Shared
    String version = property('e2e.productVersion')

    @Shared
    Path tar = Path.of(property('e2e.distTarPath'))

    @Shared
    Path zip = Path.of(property('e2e.distZipPath'))

    @Shared
    Map<String, Integer> tarFiles = readTar(tar)

    // FR4: the archive names and the folder both unpack to
    def "FR4: the archives are named gnomish-<version> and unpack to gnomish-<version>/"() {
        expect:
        tar.fileName.toString() == "gnomish-${version}.tar.gz"
        zip.fileName.toString() == "gnomish-${version}.zip"
        tarFiles.keySet().every { it.startsWith("gnomish-${version}/") }
        zipFiles().every { it.startsWith("gnomish-${version}/") }
    }

    // FR4: "A distribution archive with a launcher" — the full file list, nothing missing, nothing extra
    def "FR4: the #kind archive holds exactly the launcher, the one jar, the terms, the readme and share/"() {
        expect:
        files == expectedLayout()

        where:
        kind | files
        'tar' | tarFiles.keySet()
        'zip' | zipFiles()
    }

    // FR5: the launcher is shipped executable; nothing else is
    def "FR5: bin/gnomish is the only executable file in the tar, with mode 0755"() {
        expect:
        tarFiles.findAll { name, mode ->
            (mode & 0111) != 0
        } == [("gnomish-${version}/bin/gnomish".toString()): 0755]
        tarFiles.values().every { it in [0644, 0755] }
    }

    // FR4: the recipe is copied at build time, never committed twice
    def "FR4: the repository holds no copy of the sandbox-image recipe outside docs/examples/"() {
        given:
        def root = RepoSourceTree.repoRoot()
        def tracked = git(root, 'ls-files', '-z').split('\u0000').findAll { it }

        expect: 'the scan really reached the repository'
        tracked.size() > 100

        when:
        def recipeFiles = tracked.findAll {
            it.startsWith("${RECIPE}/") && Files.size(root.resolve(it)) > 0
        }
        def copies = tracked.findAll { path ->
            !path.startsWith("${RECIPE}/") && recipeFiles.any {
                sameBytes(root.resolve(path), root.resolve(it))
            }
        }
        def namedCopies = tracked.findAll {
            it.contains('sandbox-image/') && !it.startsWith("${RECIPE}/")
        }

        then:
        !recipeFiles.isEmpty()
        copies == []
        namedCopies == []
    }

    private Set<String> expectedLayout() {
        def top = "gnomish-${version}"
        def layout = [
            "${top}/bin/gnomish",
            "${top}/lib/gnomish-${version}.jar",
            "${top}/LICENSE",
            "${top}/NOTICE",
            "${top}/README.md",
        ].collect { it.toString() } as Set
        def examples = RepoSourceTree.repoRoot().resolve('docs/examples')
        Files.walk(examples).withCloseable { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { file ->
                def relative = examples.relativize(file).toString().replace(File.separatorChar, '/' as char)
                layout << (relative.startsWith('sandbox-image/')
                        ? "${top}/share/${relative}"
                        : "${top}/share/examples/${relative}").toString()
            }
        }
        layout
    }

    private Set<String> zipFiles() {
        new ZipFile(zip.toFile()).withCloseable { archive ->
            archive.entries().toList().findAll {
                !it.directory
            }.collect {
                it.name
            } as Set
        }
    }

    /**
     * The regular files of a gzipped tar, with their modes. A tar header is one 512-byte block:
     * the name in bytes 0-99, the octal mode at 100, the octal size at 124, the type at 156 and
     * the ustar name prefix at 345; a GNU {@code L} entry carries the next entry's long name.
     */
    private static Map<String, Integer> readTar(Path archive) {
        Map<String, Integer> files = [:]
        new GZIPInputStream(Files.newInputStream(archive)).withCloseable { input ->
            String longName = null
            while (true) {
                byte[] header = input.readNBytes(512)
                if (header.length < 512 || header.every { it == 0 }) {
                    break
                }
                long size = octal(header, 124, 12)
                byte[] body = input.readNBytes((int) ((size + 511).intdiv(512) * 512))
                char type = (char) header[156]
                if (type == ('L' as char)) {
                    longName = text(body, 0, (int) size)
                    continue
                }
                String prefix = text(header, 345, 155)
                String name = longName ?: (prefix ? "${prefix}/${text(header, 0, 100)}" : text(header, 0, 100))
                longName = null
                if (type == ('0' as char) || type == (0 as char)) {
                    files[name] = (int) octal(header, 100, 8) & 07777
                }
            }
        }
        files
    }

    private static long octal(byte[] block, int offset, int length) {
        def digits = text(block, offset, length).trim()
        digits ? Long.parseLong(digits, 8) : 0L
    }

    private static String text(byte[] block, int offset, int length) {
        int end = offset
        while (end <offset + length && block[end] != 0) {
            end++
        }
        new String(block, offset, end - offset, StandardCharsets.UTF_8)
    }

    private static boolean sameBytes(Path a, Path b) {
        Files.size(a) == Files.size(b) && Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(b))
    }

    private static String git(Path root, String... arguments) {
        def process = new ProcessBuilder(['git', '-C', root.toString()] + arguments.toList()).start()
        def output = process.inputStream.getText(StandardCharsets.UTF_8.name())
        assert process.waitFor() == 0: "git ${arguments.join(' ')} failed: ${process.errorStream.text}"
        output
    }

    private static String property(String name) {
        def value = System.getProperty(name)
        assert value != null: "${name} is not set (see verification.gradle)"
        value
    }
}
