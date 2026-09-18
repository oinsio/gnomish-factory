package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RawCaptureOwners
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * FR11, design D12 of type-untrusted-text: production code reads text in from outside the trust
 * boundary — a subprocess stream, an HTTP response body, a document file — only in a declared mint
 * owner. This is the retargeted successor of {@code UntrustedLogTextGateSpec}, which scanned log
 * calls for accessor names until every capture family was typed and its vocabulary emptied. Same
 * machinery, different layer: the names it lost guarded sinks, and {@link UntrustedTextGateSpec}'s
 * rules (a), (b) and (c) guard those by type now.
 *
 * <p>What no type rule can guard is text the factory never wrapped. An adapter added next quarter
 * that reads {@code process.getInputStream()} or an HTTP body straight into a {@code String} and
 * logs it compiles green under every rule of the type gate, because there is no carrier for a rule
 * to see. That is the window this scan closes, and it closes it where the text enters rather than
 * where it leaves: {@link RawCaptureOwners#CAPTURE_OWNERS} is the allowlist, taken from design D3's
 * mint table, and each entry names the family its file mints or why no mint is owed.
 *
 * <p>The scan asserts it reached every allowlisted file, on the {@link BaseHeadDefaultBoundarySpec}
 * precedent: an allowlist that silently stops matching is an allowlist that permits everything. A
 * file whose capture moves away is removed from the map in the same change, which is what makes the
 * reached-every-file assertion a working gate rather than a ceremony.
 *
 * <p>Lives in {@code :bootstrap} for the same reason its predecessor did: it is a whole-tree source
 * gate, and this is the module whose {@code test} task wires {@code repoRoot}.
 */
class RawCaptureGateSpec extends Specification {

    /**
     * A subprocess's own streams. Recognized on any receiver rather than on {@code process.}: the
     * handle a factory class holds is as often named {@code launched} or {@code handle}, and a
     * receiver-keyed pattern would miss exactly the new adapter this gate exists for.
     */
    private static final Pattern PROCESS_STREAM =
    Pattern.compile('\\.get(?:Input|Error)Stream\\s*\\(\\s*\\)')

    /**
     * An HTTP response body. {@code body()} alone is far too common in this codebase — a task
     * context, a tracker comment and a recorded decision all answer it — so the shape is keyed on
     * the file naming the JDK response type as well. That is the capture point: a {@code Fresh} or
     * a cached envelope answering {@code body()} downstream is propagation of text already
     * captured, which this gate deliberately does not judge.
     */
    private static final Pattern HTTP_BODY = Pattern.compile('\\.body\\s*\\(\\s*\\)')

    /** The import that makes a {@code body()} call in the same file an HTTP capture. */
    private static final String HTTP_RESPONSE_IMPORT = 'import java.net.http.HttpResponse'

    /** A document file read into memory: every {@code Files} reader that yields text or bytes. */
    private static final Pattern FILE_READ = Pattern.compile(
    'Files\\.(?:readString|readAllLines|readAllBytes|lines|newBufferedReader)\\s*\\(')

    // FR11: every raw capture in production sits in a declared mint owner.
    def "FR11: raw capture happens only in a declared mint owner"() {
        given: 'every production source, with its comments removed'
        def sources = RepoSourceTree.productionSources()

        expect: 'the scan really reached the production tree'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        when: 'each file is asked whether it captures raw text'
        def capturing = sources
                .findAll { capturesRaw(RepoSourceTree.code(it)) }
                .collect { RepoSourceTree.relative(it) }
                .toSorted()

        then: 'no file captures outside the allowlist'
        (capturing - RawCaptureOwners.CAPTURE_OWNERS.keySet()) == []

        and: 'and every allowlisted owner still captures — a stale entry permits a file for nothing'
        (RawCaptureOwners.CAPTURE_OWNERS.keySet() as List).toSorted() - capturing == []
    }

    // D12: the detector is the gate — a seeded violation must fail it, or a green run means nothing.
    def "a seeded raw capture outside the allowlist is detected: #shape"() {
        expect:
        capturesRaw(code)

        where:
        shape | code
        'process stdout' | 'String out = new String(launched.getInputStream().readAllBytes(), UTF_8);'
        'process stderr' | 'var err = drain(process.getErrorStream());'
        'http body' | 'import java.net.http.HttpResponse;\nString json = response.body();'
        'file read' | 'String json = Files.readString(path);'
        'file lines' | 'List<String> lines = Files.readAllLines(file, UTF_8);'
    }

    // D12: the shapes that are NOT capture must stay unflagged, or the allowlist grows to hold
    //      every class in the repository and stops meaning anything.
    def "a non-capture shape is not flagged: #shape"() {
        expect:
        !capturesRaw(code)

        where:
        shape | code
        'a context body' | 'return new TaskContext(id, context.title(), context.body(), decisions);'
        'a cached envelope' | 'yield fresh.body();'
        'a carrier read' | 'String text = result.stdout().forParsing();'
        'a file write' | 'Files.writeString(path, json, UTF_8);'
    }

    // D12: an allowlisted mint owner reads raw and is allowed to — the exemption must work, or a
    //      green gate would mean "the allowlist is ignored" as easily as "the tree is clean".
    def "an allowlisted mint owner captures and is permitted"() {
        given: 'the branch-document mint owner, which reads the state file and mints at the read'
        def owner = 'adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/GitTaskStore.java'

        expect: 'it really captures'
        capturesRaw(RepoSourceTree.code(RepoSourceTree.repoRoot().resolve(owner).toFile()))

        and: 'and the allowlist names it with its family'
        RawCaptureOwners.CAPTURE_OWNERS[owner].startsWith('BRANCH_DOCUMENT')
    }

    // D12: the reached-every-file half. Removing an owner's capture while its entry stays is the
    //      drift BaseHeadDefaultBoundarySpec's precedent guards against; this pins that it fails.
    def "an allowlisted file that no longer captures fails the reached-every-file assertion"() {
        given: 'an allowlist carrying one entry whose file captures nothing'
        def stale = RawCaptureOwners.CAPTURE_OWNERS.keySet() + [
            'bootstrap/src/main/java/com/github/oinsio/gnomish/Gnomish.java'
        ]

        and: 'the production files that really capture'
        def capturing = RepoSourceTree.productionSources()
                .findAll { capturesRaw(RepoSourceTree.code(it)) }
                .collect { RepoSourceTree.relative(it) }

        expect:
        ((stale as List).toSorted() - capturing) != []
    }

    /** True when the source captures text from outside the trust boundary. */
    private static boolean capturesRaw(String code) {
        PROCESS_STREAM.matcher(code).find()
                || FILE_READ.matcher(code).find()
                || (code.contains(HTTP_RESPONSE_IMPORT) && HTTP_BODY.matcher(code).find())
    }
}
