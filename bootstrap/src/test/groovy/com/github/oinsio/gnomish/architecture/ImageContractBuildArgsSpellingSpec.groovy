package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR6, FR7, FR8, FR10 of add-sandbox-base-image (design D16, single-owner row 1): the contract file
 * becomes build arguments in one place, {@code scripts/image-contract-build-args.sh}, which derives
 * each argument's name from its key by rule. This gate keeps the readers of that translation — the
 * publishing workflows and the E2E base fixture — from growing a key → argument table again: no file
 * under {@code .github/workflows/} and none under {@code test-fixtures/src/main} spells a contract key
 * followed by {@code =} or a derived argument name, comments included (a table in a comment is still
 * a second spelling to keep in step).
 *
 * <p>The spellings come from the keys of {@code sandbox-image/image-contract.properties}, so a key
 * added there is guarded with no edit here; the rule turning a key into a name is restated below as
 * this gate's own oracle, apart from the script on purpose. Every regular file is read as bytes, so a
 * binary resource is scanned rather than skipped, and the scan asserts it reached every file.
 */
class ImageContractBuildArgsSpellingSpec extends Specification {

    private static final String SOURCE = 'sandbox-image/image-contract.properties'

    /** The trees whose files must not spell the contract's keys or argument names. */
    private static final List<String> SCANNED = [
        '.github/workflows',
        'test-fixtures/src/main'
    ]

    /** The readers the scan must reach: the two publishing workflows and the base fixture. */
    private static final List<String> KNOWN_READERS = [
        '.github/workflows/release.yml',
        '.github/workflows/sandbox-image-rebuild.yml',
        'test-fixtures/src/main/groovy/com/github/oinsio/gnomish/sandbox/environment/SandboxBaseImage.groovy',
    ]

    // FR6: the spellings are not vacuous — the three keys of today and their argument names are among them
    def "FR6: the guarded spellings cover every key of the contract file and its derived argument name"() {
        expect:
        spellings().containsAll([
            'contract-version=',
            'guard-alias=',
            'guard-port=',
            'CONTRACT_VERSION',
            'GUARD_ALIAS',
            'GUARD_PORT'
        ])
    }

    // FR6, FR7, FR8, FR10: the scan reached every regular file of both trees, the readers among them
    def "FR6: the scan reads every regular file under #tree"() {
        given:
        Path start = root().resolve(tree)

        when:
        def reached = scanned(tree)*.relative

        then:
        reached.size() == regularFileCount(start)
        reached.size() > 0

        where:
        tree << SCANNED
    }

    def "FR6: the scan reaches the publishing workflows and the base fixture"() {
        expect:
        SCANNED.collectMany { scanned(it)*.relative }.containsAll(KNOWN_READERS)
    }

    // FR6, FR7, FR8, FR10 (design D16): every reader takes the arguments from the script
    def "FR6: no file under the workflows or the test fixtures spells a contract key with = or an argument name"() {
        when:
        def found = SCANNED.collectMany { tree ->
            scanned(tree).collect { file ->
                def hits = spellings().findAll {
                    (file.text as String).contains(it)
                }
                hits ? "${file.relative}: ${hits.join(', ')}".toString() : null
            }.findAll()
        }

        then:
        found == []
    }

    /** Each key of the contract file followed by {@code =}, and its argument name by the rule. */
    private static List<String> spellings() {
        def keys = Files.readAllLines(root().resolve(SOURCE)).findAll {
            it ==~ /[a-z][a-z0-9-]*=.*/
        }.collect {
            it.substring(0, it.indexOf('='))
        }
        assert !keys.isEmpty(): "${SOURCE} carries no key=value line"
        keys.collect {
            "${it}=".toString()
        } + keys.collect {
            it.toUpperCase(Locale.ROOT).replace('-', '_')
        }
    }

    /** Every regular file under {@code tree}, read whole as bytes. */
    private static List<Map> scanned(String tree) {
        Files.walk(root().resolve(tree)).withCloseable { paths ->
            paths.filter { Files.isRegularFile(it) }.sorted().toList()
        }.collect { Path file ->
            [relative: root().relativize(file).toString(), text: new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)]
        }
    }

    /** An independent count of the regular files under {@code start}, to check the scan read them all. */
    private static long regularFileCount(Path start) {
        long count = 0
        start.toFile().eachFileRecurse { if (it.isFile()) count++ }
        count
    }

    private static Path root() {
        RepoSourceTree.repoRoot()
    }
}
