package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * Design D10 of define-executor-contract, the {@code ProgramChannel} row: the operation words a
 * {@code program} executor is addressed with ({@code describe}, {@code start}, {@code poll}) and the
 * {@code GNOMISH_*} variables it is handed ({@code GNOMISH_RESULT_FILE}, {@code GNOMISH_ANSWER_FILE},
 * {@code GNOMISH_PENDING_KEY}) are spelled by {@code ProgramChannel} alone. The describe preflight
 * ({@code ProgramDescribe}) and the program executor ({@code ProgramSpawn}, for {@code start} and
 * {@code poll}) take them from there; a second spelling would compile, pass its own spec, and drift
 * from the wire form on the next change of a word (FR23, FR5).
 *
 * <p>A whole-tree text scan in {@code :bootstrap}, the shape of {@link TerminalRenderOwnerSpec},
 * comments stripped. An operation word is scanned as a quoted literal; a variable name anywhere in
 * code, since a message that names the variable is a spelling too. The same three words are also
 * words of other vocabularies, and those files are listed below with what their literal means; each
 * listed file must still spell its literal, so a row left behind after a move fails the gate.
 *
 * <p>FR5, FR23 of define-executor-contract.
 */
class ProgramChannelOwnerSpec extends Specification {

    private static final String OWNER = 'application/src/main/java/com/github/oinsio/gnomish/app/describe/ProgramChannel.java'

    private static final String DOCKER = 'sandbox/docker/src/main/java/com/github/oinsio/gnomish/sandbox/environment/'

    /** What only the owner may spell: the operation words as quoted literals, the variable names anywhere in code. */
    private static final List<String> OWNED = [
        '"describe"',
        '"start"',
        '"poll"',
        'GNOMISH_RESULT_FILE',
        'GNOMISH_ANSWER_FILE',
        'GNOMISH_PENDING_KEY',
    ]

    /** Files spelling an owned literal in another vocabulary: path → literal → what it means there. */
    private static final Map<String, Map<String, String>> OTHER_VOCABULARIES = [
        (DOCKER + 'DockerCommands.java'): ['"start"': "docker's own `start` subcommand of a stopped box"],
        (DOCKER + 'GuardCommands.java'): ['"start"': "docker's own `start` subcommand of the egress guard"],
        (DOCKER + 'EgressGuard.java'): ['"start"': 'the name of a guard repair step in its log line'],
        ('executor-contract/src/main/java/com/github/oinsio/gnomish/executorcontract/Capability.java'):
        ['"poll"': "the describe capability's wire token, a value of the describe schema, not an operation word"],
        ('adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/program/DescribedProgram.java'):
        ['"describe"': 'the name of a record component in its null check'],
        ('bootstrap/src/main/java/com/github/oinsio/gnomish/app/conformance/KitCase.java'):
        ['"describe"': "the conformance kit's case name, a word of the kit's case vocabulary (kept in sync with steps/executor-run.sh)"],
    ]

    // FR5, FR23: each operation word and program variable has exactly one production home, the owner
    def "FR5, FR23: #literal is spelled in production by ProgramChannel and by no other program-addressing file"() {
        given: 'every production source of the build, comments removed'
        def sources = RepoSourceTree.productionSources()

        expect: 'the scan really reached the tree, the owner included'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        sources.collect { RepoSourceTree.relative(it) }.contains(OWNER)

        when:
        def spellers = sources.findAll {
            RepoSourceTree.code(it).contains(literal)
        }
        .collect { RepoSourceTree.relative(it) }
        .findAll { !OTHER_VOCABULARIES[it]?.containsKey(literal) }
        .sort()

        then: 'the owner, and only the owner — an extra file is a hand copy, none a moved channel'
        spellers == [OWNER]

        where:
        literal << OWNED
    }

    // The exemptions are asserted reached: a listed file that no longer spells its literal is a stale row
    def "every listed other-vocabulary file still spells its literal: #path"() {
        given:
        def file = RepoSourceTree.repoRoot().resolve(path).toFile()

        expect:
        file.isFile()
        literals.keySet().every { RepoSourceTree.code(file).contains(it) }

        where:
        path << OTHER_VOCABULARIES.keySet().toList()
        literals = OTHER_VOCABULARIES[path]
    }

    // The detector is the gate: a seeded copy in code is found, a mention in a comment is not
    def "the detector: #shape"() {
        expect:
        OWNED.any { RepoSourceTree.codeOnly(line).contains(it) } == detected

        where:
        shape | line || detected
        'a hand-spelled poll word' | 'argv.add("poll");' || true
        'a hand-spelled variable' | 'env.put("GNOMISH_PENDING_KEY", key);' || true
        'a javadoc mention' | ' * the key in {@code GNOMISH_PENDING_KEY}' || false
        'a trailing comment' | 'argv.add(ProgramChannel.POLL); // "poll"' || false
    }
}
