package com.github.oinsio.gnomish.distribution

import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The one translation of the image contract file into the base's build arguments,
 * {@code scripts/image-contract-build-args.sh} (FR6, FR7, FR8, FR10 of add-sandbox-base-image;
 * design D16, single-owner row 1). The release's {@code image} job, the weekly rebuild and the E2E
 * base fixture all build from its output, so its rule is pinned here once: the name derived from the
 * key (upper-cased, {@code -} to {@code _}), comments and blank lines skipped, and every line it
 * cannot translate faithfully refused naming the file and the line — never stripped, since the
 * factory's reader strips a value and its runtime check compares the label exactly.
 *
 * <p>The inputs the spec writes are data files, not executables; the expected values of the
 * committed file are read from it independently, as {@link Properties}.
 */
class ImageContractBuildArgsScriptSpec extends Specification {

    private static final Path ROOT = RepoSourceTree.repoRoot()
    private static final Path SCRIPT = ROOT.resolve('scripts/image-contract-build-args.sh')
    private static final Path SOURCE = ROOT.resolve('sandbox-image/image-contract.properties')

    @TempDir
    Path tmp

    // FR6, FR10: the committed file yields its three arguments, in file order, with its values
    def "FR6: the committed contract file yields one argument per key, in file order, with its values"() {
        given:
        def values = new Properties()
        Files.newInputStream(SOURCE).withCloseable { values.load(it) }

        when:
        def run = script(ROOT, [SOURCE.toString()])

        then:
        run.exit == 0
        run.output.readLines() == [
            "CONTRACT_VERSION=${values.getProperty('contract-version')}",
            "GUARD_ALIAS=${values.getProperty('guard-alias')}",
            "GUARD_PORT=${values.getProperty('guard-port')}",
        ]*.toString()
    }

    // FR6 (design D16): with no argument the script reads the default path from the working directory
    def "FR6: with no argument the script reads sandbox-image/image-contract.properties from the working directory"() {
        expect:
        script(ROOT, []) == script(ROOT, [SOURCE.toString()])
        script(ROOT, []).exit == 0
    }

    // FR6 (design D16): the name is derived by rule, so a new key needs no edit to the script
    def "FR6: comments and blank lines are skipped, and a new key yields its derived name"() {
        given:
        def input = written('''\
                # a comment
                contract-version=4

                #guard-alias=commented-out
                guard-alias=egress-proxy
                registry-mirror-2=https://mirror.example/v2?a=b
                '''.stripIndent())

        when:
        def run = script(tmp, [input.toString()])

        then:
        run.exit == 0
        run.output == 'CONTRACT_VERSION=4\nGUARD_ALIAS=egress-proxy\nREGISTRY_MIRROR_2=https://mirror.example/v2?a=b\n'
    }

    // FR6 (design D16): a line the script cannot translate faithfully is refused, naming the line
    def "FR6: #defect on line 2 is refused with exit 1, naming the file and the line, and no argument is printed"() {
        given:
        def input = written("contract-version=1\n${line}\nguard-port=8080\n")

        when:
        def run = script(tmp, [input.toString()])

        then:
        run.exit == 1
        run.output.startsWith("::error title=Image contract,file=${input},line=2::${input} line 2 ${why}")
        run.output.readLines().size() == 1

        where:
        defect | line || why
        'an empty value' | 'guard-alias=' || 'gives guard-alias an empty value'
        'a malformed line' | 'Guard_Alias=x' || 'is not a key=value line'
        'a line with no =' | 'guard-alias' || 'is not a key=value line'
        'a key after a space' | ' guard-alias=x' || 'is not a key=value line'
        'a trailing space' | 'guard-alias=x ' || 'gives guard-alias a value that begins or ends with whitespace'
        'a leading space' | 'guard-alias= x' || 'gives guard-alias a value that begins or ends with whitespace'
        'a trailing tab' | 'guard-alias=x\t' || 'gives guard-alias a value that begins or ends with whitespace'
        'a CRLF line ending' | 'guard-alias=x\r' || 'gives guard-alias a value that begins or ends with whitespace'
    }

    // FR6: a missing file is refused, never read as an empty argument list
    def "FR6: a file that does not exist is refused with exit 1, naming it"() {
        given:
        def missing = tmp.resolve('absent.properties')

        when:
        def run = script(tmp, [missing.toString()])

        then:
        run.exit == 1
        run.output == "::error title=Image contract::cannot read the image contract file ${missing}\n"
    }

    // FR6: the workflows pass at most the file; more is a usage error
    def "FR6: two arguments fail with exit 2"() {
        when:
        def run = script(ROOT, [
            SOURCE.toString(),
            SOURCE.toString()
        ])

        then:
        run.exit == 2
        run.output.contains("usage: ${SCRIPT} [<properties file>]")
    }

    private Path written(String text) {
        Files.writeString(tmp.resolve('contract.properties'), text)
    }

    private static Map script(Path workingDirectory, List<String> args) {
        def builder = new ProcessBuilder(['sh', SCRIPT.toString()] + args)
        .directory(workingDirectory.toFile())
        .redirectErrorStream(true)
        .redirectInput(ProcessBuilder.Redirect.from(new File('/dev/null')))
        TestChildEnvironment.cleared(builder)
        def process = builder.start()
        def output = process.inputStream.text
        [exit: process.waitFor(), output: output]
    }
}
