package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The license CI job's distribution-terms checks (FR1, FR3 of add-project-license, design D2),
 * and the release workflow's check of the unpacked archive (NFR-S2 of add-release-pipeline,
 * design D6), are a shell script, {@code scripts/check-distribution-terms.sh}; on CI they only ever run
 * against a healthy repository, so a regression in the script (a dropped {@code cmp}, a lost
 * exit code) would stay green until a real incident. This spec drives the script's red paths
 * against a throwaway repository root, and pins that both workflows really call it.
 */
class DistributionTermsScriptSpec extends Specification {

    private static final String LICENSE = 'Apache License\nVersion 2.0\n'
    private static final String NOTICE = 'Gnomish Factory\n'

    @TempDir
    Path root

    // FR1: the "License file missing" scenario — the job fails naming the missing file
    def "FR1: presence fails naming #missing when it is absent from the root"() {
        given: 'a root holding only the other file'
        writeRootTerms()
        Files.delete(root.resolve(missing))

        when:
        def run = runScript('presence')

        then:
        run.exit != 0
        run.output.contains("::error file=${missing}::${missing} is missing from the repository root")

        where:
        missing << ['LICENSE', 'NOTICE']
    }

    // FR1: the happy path, so the red features above are not passing on a broken script
    def "FR1: presence passes when both files are present"() {
        given:
        writeRootTerms()

        expect:
        runScript('presence').exit == 0
    }

    // FR3: both jars carry the root bytes
    def "FR3: identity passes when every jar carries the root files byte for byte"() {
        given:
        writeRootTerms()
        jar('a/libs/a.jar', [LICENSE: LICENSE, NOTICE: NOTICE])
        jar('b/libs/b.jar', [LICENSE: LICENSE, NOTICE: NOTICE])

        expect:
        runScript('identity', 'a/libs', 'b/libs').exit == 0
    }

    // FR3: the "A jar entry drifts from the root file" scenario, plus a missing entry
    def "FR3: identity fails naming the jar and #entry when the entry #how"() {
        given: 'a healthy jar and one whose entry drifts'
        writeRootTerms()
        jar('a/libs/a.jar', [LICENSE: LICENSE, NOTICE: NOTICE])
        jar('b/libs/b.jar', entries)

        when:
        def run = runScript('identity', 'a/libs', 'b/libs')

        then: 'the job fails naming the jar and the entry, and only that one'
        run.exit != 0
        run.output.contains("::error::b/libs/b.jar: META-INF/${entry} is missing or differs from the root ${entry}")
        !run.output.contains('a/libs/a.jar')

        where:
        entry | how | entries
        'NOTICE' | 'differs by one byte' | [LICENSE: LICENSE, NOTICE: NOTICE.replace('G', 'g')]
        'LICENSE' | 'differs by one byte' | [LICENSE: LICENSE + ' ', NOTICE: NOTICE]
        'NOTICE' | 'is missing' | [LICENSE: LICENSE]
    }

    // FR3: a stray second artifact must not make the check compare the wrong file
    def "FR3: identity fails when a jar directory does not hold exactly one jar"() {
        given:
        writeRootTerms()
        jar('a/libs/a.jar', [LICENSE: LICENSE, NOTICE: NOTICE])
        jar('a/libs/a-plain.jar', [LICENSE: LICENSE, NOTICE: NOTICE])
        Files.createDirectories(root.resolve('b/libs'))

        when:
        def run = runScript('identity', 'a/libs', 'b/libs')

        then:
        run.exit != 0
        run.output.contains('::error::expected exactly one jar in a/libs')
        run.output.contains('::error::expected exactly one jar in b/libs')
    }

    // FR7: the job runs both checks through this script, so the features above test what CI runs
    def "FR7: the license workflow runs both checks through the script"() {
        given:
        def workflow = Files.readString(RepoSourceTree.repoRoot().resolve('.github/workflows/license-gate.yml'))

        expect:
        workflow.contains('run: bash scripts/check-distribution-terms.sh presence')
        workflow.contains('run: bash scripts/check-distribution-terms.sh identity bootstrap/build/libs gnomish-plugin-api/build/libs')
    }

    // NFR-S2 of add-release-pipeline: an unpacked archive whose copies equal the root files
    def "NFR-S2: archive passes when the unpacked folder and its jar carry the root files"() {
        given:
        writeRootTerms()
        unpacked([LICENSE: LICENSE, NOTICE: NOTICE])

        expect:
        runScript('archive', 'gnomish-1.0.0').exit == 0
    }

    // NFR-S2: the "An archive copy drifts from the root file" scenario
    def "NFR-S2: archive fails naming gnomish-1.0.0/#file when the copy #how"() {
        given:
        writeRootTerms()
        unpacked(copies)

        when:
        def run = runScript('archive', 'gnomish-1.0.0')

        then:
        run.exit != 0
        run.output.contains("::error file=gnomish-1.0.0/${file}::gnomish-1.0.0/${file} ${message}")

        where:
        file | how | copies | message
        'NOTICE' | 'differs by one byte' | [LICENSE: LICENSE, NOTICE: NOTICE.replace('G', 'g')] | 'differs from the root NOTICE'
        'LICENSE' | 'differs by one byte' | [LICENSE: LICENSE + ' ', NOTICE: NOTICE] | 'differs from the root LICENSE'
        'LICENSE' | 'is missing' | [NOTICE: NOTICE] | 'is missing from the archive'
    }

    // NFR-S2: the archive's jar is checked too — a lib/ with no jar is not a pass
    def "NFR-S2: archive fails when lib/ holds no jar"() {
        given:
        writeRootTerms()
        unpacked([LICENSE: LICENSE, NOTICE: NOTICE])
        Files.delete(root.resolve('gnomish-1.0.0/lib/gnomish-1.0.0.jar'))

        when:
        def run = runScript('archive', 'gnomish-1.0.0')

        then:
        run.exit != 0
        run.output.contains('::error::expected exactly one jar in gnomish-1.0.0/lib')
    }

    // NFR-S2: the release runs the archive mode through this script (design D6, step 6)
    def "NFR-S2: the release workflow checks the unpacked archive through the script"() {
        expect:
        Files.readString(RepoSourceTree.repoRoot().resolve('.github/workflows/release.yml'))
                .contains('run: bash scripts/check-distribution-terms.sh archive "unpacked/gnomish-')
    }

    /** An unpacked archive: the given top-level copies, and a jar carrying the root files. */
    private void unpacked(Map<String, String> copies) {
        Files.createDirectories(root.resolve('gnomish-1.0.0'))
        copies.each { name, content ->
            Files.writeString(root.resolve("gnomish-1.0.0/${name}"), content)
        }
        jar('gnomish-1.0.0/lib/gnomish-1.0.0.jar', [LICENSE: LICENSE, NOTICE: NOTICE])
    }

    private void writeRootTerms() {
        Files.writeString(root.resolve('LICENSE'), LICENSE)
        Files.writeString(root.resolve('NOTICE'), NOTICE)
    }

    private void jar(String relative, Map<String, String> metaInf) {
        def path = root.resolve(relative)
        Files.createDirectories(path.parent)
        new ZipOutputStream(Files.newOutputStream(path)).withCloseable { zip ->
            metaInf.each { name, content ->
                zip.putNextEntry(new ZipEntry("META-INF/${name}"))
                zip.write(content.bytes)
                zip.closeEntry()
            }
        }
    }

    private Map runScript(String... args) {
        def script = RepoSourceTree.repoRoot().resolve('scripts/check-distribution-terms.sh').toString()
        def process = new ProcessBuilder(['bash', script] + args.toList())
        .directory(root.toFile())
        .redirectErrorStream(true)
        .start()
        def output = process.inputStream.text
        [exit: process.waitFor(), output: output]
    }
}
