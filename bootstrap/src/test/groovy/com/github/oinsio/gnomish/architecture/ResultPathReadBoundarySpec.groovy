package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * Design D10 of define-executor-contract, row {@code ResultFileReader}: a file an executor, a check
 * or a gnome writes for the factory to read back (a result path) is read only through the owner,
 * which caps the read, refuses a link and binds every free-text field as {@code UntrustedText}. A
 * second read of the same path — a bare {@code Files.readString} of the answer file, an
 * {@code environment.readFile} of the findings file parsed by hand — compiles, passes its own spec
 * and reopens the uncapped read the owner closed.
 *
 * <p><b>The rule, and why it is keyed on vocabulary.</b> Source text cannot follow a path through a
 * variable, so the scan keys on what a result-path reader cannot avoid spelling: a file that names
 * a result path ({@link #RESULT_PATH}: the {@code GNOMISH_*_FILE} variables, the answer file's one
 * spelling ({@code EnvelopePaths.answerFile}, FR9 of extract-agent-round-runner), the findings path) <em>and</em> performs a raw read ({@link #RAW_READ}: the
 * {@code Files} reads, a channel {@code readFile}, a git blob read) is a reader. Every reader must be
 * allowlisted, and so must every file that builds the owner's entry points ({@link #ENTRY}: a
 * {@code ResultFileReader}, a host source), so a new consumer is a visible allowlist row. The
 * non-result reads D10 exempts (law sources, envelopes, the ledger, the environments' own
 * {@code readFile}) name no result path and need no row. The answer file's spelling itself is
 * single-owned by {@code DecisionPathOwnerSpec}, which this gate leans on.
 *
 * <p>An answer file has one read on top of that (FR21, NFR-R5, M8 of extract-agent-round-runner;
 * design D15, part 4, and D8's row "The answer read"): {@code AnswerFileRead}, over the owner and the
 * contract's answer reading, for the live round and the recovery of a snapshot alike. A file that
 * names an answer path and reads its medium hands the bytes to that one function, and the contract's
 * {@code Answer.read} is called only by it and by the readers of a result's own answer slot — so no
 * class parses an answer file by itself, and recovery cannot read the same bytes otherwise.
 *
 * <p>The whole production tree is scanned, {@code :test-fixtures} included, comments stripped. M3,
 * FR6, FR24 of define-executor-contract; testing.md, "Fixtures assemble through production owners".
 */
class ResultPathReadBoundarySpec extends Specification {

    private static final String CONTRACT = 'executor-contract/src/main/java/com/github/oinsio/gnomish/executorcontract/'

    /** The owner: it defines the read and is exempt from the scan; asserted present. */
    private static final Map<String, String> OWNER = [
        (CONTRACT + 'ResultFileReader.java'): 'the capped, typed read',
        (CONTRACT + 'ResultSource.java') : 'the one byte-reading seam the owner reads through',
        (CONTRACT + 'HostResultFile.java') : 'the host file source: NOFOLLOW_LINKS, never deletes',
    ]

    /** Every file allowed to read a result path or build the owner's entry points, each with why. */
    private static final Map<String, String> READERS = [
        ('adapters/src/main/java/com/github/oinsio/gnomish/adapter/check/CheckFindings.java'): 'the findings file (GNOMISH_FINDINGS_FILE): its own reader, 256 KiB',
        ('application/src/main/java/com/github/oinsio/gnomish/app/port/agent/AnswerFileRead.java'): 'the one answer read every answer file goes through, live and at recovery, 4 MiB (FR21 of extract-agent-round-runner)',
        ('adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/SnapshotTipCheck.java'): 'the answer blob of a snapshot on resume, handed to the one answer read the live round uses',
        ('application/src/main/java/com/github/oinsio/gnomish/app/describe/ProgramDescribe.java'): 'the describe answer (GNOMISH_RESULT_FILE) of a program executor, through the environment channel, 64 KiB',
        ('adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/program/ProgramResultRead.java'): 'the result file (GNOMISH_RESULT_FILE, 1 MiB) of a program run, through the environment channel (task 7.1); its answer file (GNOMISH_ANSWER_FILE) goes to the one answer read',
    ]

    /** Decorating environments (D19): {@code readFile} passed through, no reader row (D10), held so below. */
    private static final Map<String, String> ENVIRONMENTS = [
        ('bootstrap/src/main/java/com/github/oinsio/gnomish/app/conformance/WireObservation.java'): 'the conformance kit\'s decorator: judges the bytes its delegate read against the schema files and hands them back unchanged (design D19, FR24)',
    ]

    /** The modules that hold or could hold a result-path read; the scan must reach each. */
    private static final List<String> MODULES = [
        'adapters',
        'adapters/agent',
        'adapters/git',
        'adapters/github',
        'application',
        'bootstrap',
        'domain',
        'executor-contract',
        'sandbox/core',
        'sandbox/docker',
        'subprocess',
        'test-fixtures',
    ]

    /** Every file allowed to read an answer path's medium: each hands the bytes to the one answer read. */
    private static final Map<String, String> ANSWER_READERS = [
        ('adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/SnapshotTipCheck.java'): 'the snapshot\'s answer blob at recovery',
        ('adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/program/ProgramResultRead.java'): 'a program\'s answer file, beside its result file',
    ]

    /**
     * The callers of the contract's {@code Answer.read}: the one answer read, the contract's own
     * binding, and the readers of an answer a result carries in its slot — never an answer file.
     */
    private static final Map<String, String> ANSWER_PARSERS = [
        ('application/src/main/java/com/github/oinsio/gnomish/app/port/agent/AnswerFileRead.java'): 'the one answer read',
        (CONTRACT + 'AnswerJson.java'): 'the contract\'s binding of an answer inside a result document',
        ('adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/program/ProgramCompletedAnswer.java'): 'the answer a completed result carries, by role',
        ('bootstrap/src/main/java/com/github/oinsio/gnomish/app/conformance/CaseJudgement.java'): 'the conformance kit judging the answer a subject reported',
    ]

    static final String ANSWER_PATH = /GNOMISH_ANSWER_FILE|\bDECISIONS_DIR\b|\b(decision|answer)File(Path)?\b/
    static final String ONE_ANSWER_READ = /\bAnswerFileRead\s*\.\s*(read|in)\s*\(/
    static final String ANSWER_PARSE = /\bAnswer\s*\.\s*read\s*\(/

    static final String RESULT_PATH = /GNOMISH_(FINDINGS|RESULT|ANSWER)_FILE|\bDECISIONS_DIR\b|\b(findings|decision|result|answer)File(Path)?\b|\bfindingsPath\b/
    static final String RAW_READ = /\.readFile\s*\(|\bFiles\.(readString|readAllBytes|readAllLines|lines|newInputStream|newBufferedReader|newByteChannel)\s*\(|\bnew\s+(FileInputStream|FileReader|RandomAccessFile)\s*\(|\.(readAtRegister|readBlob|show)\s*\(/
    static final String ENTRY = /\bnew\s+ResultFileReader\s*[(<]|\bResultSource\s*\.\s*hostFile\s*\(|\bimplements\b[^{;]*\bResultSource\b/

    // M3, FR6: only the allowlisted files read a result path; each of them is reached and still reads
    def "M3, FR6: a result path is read in production only by the owner and the allowlisted readers"() {
        given: 'every production source, comments stripped'
        def tree = productionCode()

        expect: 'the scan really reached the tree'
        tree.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        and: 'each allowlisted reader is reached and still reads a result path'
        READERS.keySet().every {
            tree.containsKey(it) && readsResultPath(tree[it])
        }

        and: 'no other file does'
        offenders(tree) == []
    }

    // Scenario: executor/result-channel / The answer read has one caller path for live and recovery
    // FR21, NFR-R5, M8 of extract-agent-round-runner: an answer path's medium is read only to hand the
    //     bytes to the one answer read, and an answer is parsed only there or from a result's slot
    def "FR21, M8: every answer-file read goes through the one answer read, and nothing else parses an answer file"() {
        given:
        def tree = productionCode()

        expect: 'every allowlisted reader is reached, reads an answer path and hands it to the one read'
        ANSWER_READERS.keySet().every {
            tree.containsKey(it) && readsAnswerPath(tree[it]) && tree[it] =~ ONE_ANSWER_READ
        }

        and: 'every allowlisted parser is reached and still parses'
        ANSWER_PARSERS.keySet().every {
            tree.containsKey(it) && tree[it] =~ ANSWER_PARSE
        }

        and: 'no other file does either'
        answerOffenders(tree) == []
    }

    // FR21: a planted class parsing an answer file itself is named by the same verdict
    def "FR21: a planted class that reads GNOMISH_ANSWER_FILE and parses it with Answer.read is reported"() {
        given:
        def planted = '''
            final class PlantedAnswerRead {
                AnswerReading answer(TaskExecutionEnvironment environment, JsonNode tree) {
                    environment.readFile(System.getenv("GNOMISH_ANSWER_FILE"), 1024);
                    return Answer.read(tree, UntrustedText::agent);
                }
            }
        '''
        def path = 'adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/PlantedAnswerRead.java'
        def tree = productionCode() + [(path): RepoSourceTree.stripComments(planted)]

        expect:
        answerOffenders(tree) == [path]
    }

    def "FR24: #path is an execution environment that reads through its delegate and builds no result reader"() {
        given:
        def code = productionCode()[path] ?: ''
        expect:
        code =~ /\bimplements\s+TaskExecutionEnvironment\b/ && code =~ /\bdelegate\.readFile\s*\(/
        !(code =~ ENTRY) && !readsResultPath(code)

        where:
        path << ENVIRONMENTS.keySet().toList()
    }

    // M3: the owner's files are where the allowlist says, so a move cannot leave the exemption naming nothing
    def "M3: every owner file is reached and declares the type it is named after"() {
        given:
        def scanned = RepoSourceTree.productionSources().collectEntries {
            [(RepoSourceTree.relative(it)): it]
        }

        expect:
        OWNER.keySet().every { path ->
            def file = scanned[path] as File
            def type = path.tokenize('/').last() - '.java'
            file != null && RepoSourceTree.code(file) =~ /\b(class|interface|record)\s+$type\b/
        }
    }

    // M3: a scan that skipped a module would pass over the readers in it
    def "M3: the scan reaches every module root that could hold a result-path read"() {
        when:
        def roots = RepoSourceTree.productionSources().collect {
            def relative = RepoSourceTree.relative(it)
            relative.substring(0, relative.indexOf('/src/main/'))
        }.toSet()

        then:
        roots.containsAll(MODULES)
    }

    // Scenario: executor/result-channel / Second reader is a build failure
    // M3: a planted bypass is reported by the same verdict the tree is judged by
    def "M3: a planted source that names GNOMISH_ANSWER_FILE and calls Files.readString is reported"() {
        given: 'the real tree with one bypass planted beside the allowlisted readers'
        def planted = '''
            final class PlantedDecisionRead {
                String question(Path dir) throws IOException {
                    return Files.readString(dir.resolve(System.getenv("GNOMISH_ANSWER_FILE")));
                }
            }
        '''
        def path = 'adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/PlantedDecisionRead.java'
        def tree = productionCode() + [(path): RepoSourceTree.stripComments(planted)]

        expect:
        offenders(tree) == [path]
    }

    // The detector is the gate: every read form and every result-path name is found, a mention is not
    def "the detector: #shape"() {
        expect:
        readsResultPath(RepoSourceTree.stripComments(source)) == detected

        where:
        shape | source || detected
        'answer variable read with readString' | 'var q = Files.readString(Path.of(env.get("GNOMISH_ANSWER_FILE")));' || true
        'result variable read as a stream' | 'var in = Files.newInputStream(Path.of(env.get("GNOMISH_RESULT_FILE")));' || true
        'answer variable read as lines' | 'var l = Files.readAllLines(Path.of(env.get("GNOMISH_ANSWER_FILE")));' || true
        'findings variable read as a reader' | 'var r = Files.newBufferedReader(Path.of(env.get("GNOMISH_FINDINGS_FILE")));' || true
        'findings path read through the channel' | 'var b = environment.readFile(findingsPath, CAP);' || true
        'answer file read from a blob' | 'var b = objects.readAtRegister(EnvelopePaths.answerFile(key, token).relative());' || true
        'decision directory read by git show' | 'var b = git.show(tip, DECISIONS_DIR + "/x.json");' || true
        'decision file read with a FileInputStream' | 'var in = new FileInputStream(decisionFile);' || true
        'a reader of the owner built' | 'var r = new ResultFileReader(CAP, UntrustedText::agent);' || true
        'a host source built' | 'var s = ResultSource.hostFile(path);' || true
        'a source implemented' | 'final class BoxSource implements Supplier<String>, ResultSource {}' || true
        'a name with no read' | 'var env = Map.of("GNOMISH_FINDINGS_FILE", findingsPath);' || false
        'a read of no result path' | 'var s = Files.readString(stateFile);' || false
        'a name in a comment' | '// GNOMISH_ANSWER_FILE\nvar s = Files.readString(lawFile);' || false
        'a read in a comment' | 'var p = findingsPath; /* Files.readString(p) */' || false
        'a consumer of the owner' | 'var r = CheckFindings.read(environment, findingsPath);' || false
        'a longer identifier' | 'var h = round.decisionFileHandle(); Files.readString(other);' || false
    }

    /** Every production source by its relative path, comments stripped. */
    private static Map<String, String> productionCode() {
        RepoSourceTree.productionSources().collectEntries {
            [(RepoSourceTree.relative(it)): RepoSourceTree.code(it)]
        }
    }

    /** The files that read a result path or build the owner's entry points without an allowlist row. */
    private static List<String> offenders(Map<String, String> codeByPath) {
        codeByPath.findAll { path, code ->
            readsResultPath(code) && !OWNER.containsKey(path) && !READERS.containsKey(path)
        }
        .keySet().sort()
    }

    /** Files reading an answer path past the one read, or parsing an answer outside the allowlist. */
    private static List<String> answerOffenders(Map<String, String> codeByPath) {
        codeByPath.findAll { path, code ->
            def unrouted = readsAnswerPath(code) && !(code =~ ONE_ANSWER_READ) && !OWNER.containsKey(path)
            def parses = code =~ ANSWER_PARSE && !ANSWER_PARSERS.containsKey(path) && !path.startsWith(CONTRACT)
            def unlisted = readsAnswerPath(code) && !ANSWER_READERS.containsKey(path) && !OWNER.containsKey(path)
                    && !path.endsWith('/AnswerFileRead.java')
            unrouted || parses || unlisted
        }
        .keySet().sort()
    }

    private static boolean readsAnswerPath(String code) {
        code =~ ANSWER_PATH && code =~ RAW_READ
    }

    private static boolean readsResultPath(String code) {
        (code =~ RESULT_PATH && code =~ RAW_READ) || code =~ ENTRY
    }
}
