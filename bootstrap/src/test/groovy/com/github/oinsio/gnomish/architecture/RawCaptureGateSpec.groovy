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
     * A subprocess's own streams, in both vocabularies the JDK offers: the byte streams
     * ({@code getInputStream}, {@code getErrorStream}) and the charset-decoding readers JDK 17
     * added ({@code inputReader}, {@code errorReader}) — the same capture, and the shorter pair is
     * what a new adapter is likelier to reach for. Recognized on any receiver rather than on
     * {@code process.}: the handle a factory class holds is as often named {@code launched} or
     * {@code handle}, and a receiver-keyed pattern would miss exactly the new adapter this gate
     * exists for. The write-side members ({@code getOutputStream}, {@code outputWriter}) are
     * deliberately absent: they carry text out, not in.
     *
     * <p>The property form ({@code process.inputStream}, {@code process.errorStream}) is a third
     * alternative rather than an optional {@code get} prefix, because Groovy sources in this
     * build's {@code src/main} — {@code :test-fixtures} is walked like any other module — reach
     * for it instead of the accessor, and a parenthesis-keyed pattern reads straight past it.
     */
    private static final Pattern PROCESS_STREAM =
    Pattern.compile('\\.(?:get(?:Input|Error)Stream|(?:input|error)Reader)\\s*\\(|\\.(?:input|error)Stream\\b')

    /**
     * An HTTP response body. {@code body()} alone is far too common in this codebase — a task
     * context, a tracker comment and a recorded decision all answer it — so the shape is keyed on
     * the file naming the JDK response type as well. That is the capture point: a {@code Fresh} or
     * a cached envelope answering {@code body()} downstream is propagation of text already
     * captured, which this gate deliberately does not judge.
     */
    private static final Pattern HTTP_BODY = Pattern.compile('\\.body\\s*\\(\\s*\\)')

    /**
     * The import that makes a {@code body()} call in the same file an HTTP capture. A pattern
     * rather than a literal, so the on-demand form counts too: nothing in this build forbids
     * {@code import java.net.http.*}, and a single-name match would let a new adapter past the
     * gate by changing its import style.
     */
    private static final Pattern HTTP_RESPONSE_IMPORT =
    Pattern.compile('import\\s+java\\.net\\.http\\.(?:HttpResponse|\\*)\\s*;')

    /**
     * A document file read into memory: every {@code Files} reader that yields text or bytes,
     * the stream-opening pair ({@code newInputStream}, {@code newByteChannel}) included. Those two
     * hand back a handle rather than the content, but the content is one {@code readNBytes} away
     * and the capture is the same one — omitting them let a channel file written by a task
     * subprocess be read in with nothing to see it.
     */
    private static final Pattern FILE_READ = Pattern.compile(
    'Files\\.(?:readString|readAllLines|readAllBytes|lines|newBufferedReader|newInputStream|newByteChannel)\\s*\\(')

    // FR11: every raw capture in production sits in a declared mint owner.
    def "FR11: raw capture happens only in a declared mint owner"() {
        given: 'every production source, with its comments removed'
        def sources = RepoSourceTree.productionSources()

        expect: 'the scan really reached the production tree'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        when: 'each file is asked whether it captures raw text'
        def capturing = capturingFiles(sources)

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
        'process stdout reader' | 'String out = launched.inputReader(UTF_8).lines().collect(joining());'
        'process stderr reader' | 'var err = drain(process.errorReader());'
        'process stdout property' | 'String out = process.inputStream.getText("UTF-8")'
        'process stderr property' | 'byte[] err = launched.errorStream.readAllBytes()'
        'http body' | 'import java.net.http.HttpResponse;\nString json = response.body();'
        'http body, on-demand import' | 'import java.net.http.*;\nString json = response.body();'
        'file read' | 'String json = Files.readString(path);'
        'file lines' | 'List<String> lines = Files.readAllLines(file, UTF_8);'
        'file stream' | 'try (InputStream in = Files.newInputStream(path)) { return in.readNBytes(cap); }'
        'file channel' | 'try (var ch = Files.newByteChannel(path)) { return read(ch); }'
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
        'a process stdin write' | 'try (var w = process.outputWriter()) { w.write(payload); }'
        'a process stdin property' | 'process.outputStream.withStream { it.write(payload) }'
        'a file stream write' | 'try (var out = Files.newOutputStream(path)) { out.write(bytes); }'
        'a body() with no http import' | 'return new TaskContext(id, title, context.body());'
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
        given: 'a real production file that captures nothing'
        def clean = 'bootstrap/src/main/java/com/github/oinsio/gnomish/FactoryApplication.java'

        and: 'which really is part of the scanned tree, so the fixture cannot rot into a typo'
        assert RepoSourceTree.productionSources().collect {
            RepoSourceTree.relative(it)
        }.contains(clean)

        and: 'an allowlist carrying it as a stale entry'
        def stale = RawCaptureOwners.CAPTURE_OWNERS.keySet() + [clean]

        and: 'the production files that really capture'
        def capturing = capturingFiles(RepoSourceTree.productionSources())

        expect:
        ((stale as List).toSorted() - capturing) != []
    }

    /** The sorted relative paths of those sources that capture text from outside the boundary. */
    private static List<String> capturingFiles(List<File> sources) {
        sources.findAll { capturesRaw(RepoSourceTree.code(it)) }
        .collect { RepoSourceTree.relative(it) }
        .toSorted()
    }

    /** True when the source captures text from outside the trust boundary. */
    private static boolean capturesRaw(String code) {
        PROCESS_STREAM.matcher(code).find()
                || FILE_READ.matcher(code).find()
                || (HTTP_RESPONSE_IMPORT.matcher(code).find() && HTTP_BODY.matcher(code).find())
    }
}
