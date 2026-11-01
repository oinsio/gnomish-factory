package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * Design D10 of define-executor-contract, the {@code WireSchemas} row (FR24, D19): the executor
 * contract's four schema files are loaded, and a document is judged against one, by {@code
 * WireSchemas} alone. In production a JSON Schema validator factory is built in exactly two files —
 * {@code WireSchemas} over the contract's own files and {@code SettingsSchema} over a program's
 * settings schema, a different and untrusted document. No test loads a contract schema file except
 * through the owner, so the files the round-trip spec holds the DTOs against are the files the
 * conformance kit judges a candidate with; a second loader would compile, pass its own spec, and
 * judge with a configuration of its own.
 *
 * <p>A whole-tree text scan, comments stripped, the shape of {@link ProgramChannelOwnerSpec}; the
 * test side covers every module's test tree and {@code test-fixtures/src/main}. A listed exemption
 * must still spell its shape, so a row left behind after a move fails the gate.
 *
 * <p>FR1, FR24 of define-executor-contract.
 */
class WireSchemaOwnerSpec extends Specification {

    private static final String CONTRACT = 'executor-contract/src/main/java/com/github/oinsio/gnomish/executorcontract/'

    private static final String OWNER = CONTRACT + 'WireSchemas.java'

    private static final String SETTINGS_JUDGE = CONTRACT + 'SettingsSchema.java'

    private static final String ROUND_TRIP =
    'executor-contract/src/test/groovy/com/github/oinsio/gnomish/executorcontract/WireSchemaRoundTripSpec.groovy'

    private static final String SELF = 'bootstrap/src/test/groovy/com/github/oinsio/gnomish/architecture/WireSchemaOwnerSpec.groovy'

    private static final String FACTORY_CALL = 'JsonSchemaFactory.getInstance('

    /** What only the owner may do in a test source: name a schema file's path, or build a validator factory. */
    private static final Map<String, Pattern> TEST_SHAPES = [
        'a path to one contract schema file': ~/schemas\/executor-[a-z]+\.schema\.json/,
        'a literal opening with the schema directory': ~/["']\/?schemas\//,
        'a classpath location of the schema directory': ~/classpath:schemas\//,
        'a validator factory of its own': ~/JsonSchemaFactory/,
    ]

    /** Test files spelling a shape for another reason: path → shape → why. */
    private static final Map<String, Map<String, String>> EXEMPT = [
        ('executor-contract/src/test/groovy/com/github/oinsio/gnomish/executorcontract/SettingsSchemaSpec.groovy'): [
            'a path to one contract schema file': 'a $ref a program writes into its settings schema, proved refused unfetched',
            'a classpath location of the schema directory': 'the same $ref, whose classpath scheme is the refused reach',
        ],
    ]

    // FR24: the two validator factories of production, and no third
    def "FR24: a JSON Schema validator factory is built in production by WireSchemas and SettingsSchema only"() {
        given: 'every production source of the build, comments removed'
        def sources = RepoSourceTree.productionSources()

        expect: 'the scan really reached the tree, both judges included'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        sources.collect {
            RepoSourceTree.relative(it)
        }.containsAll([OWNER, SETTINGS_JUDGE])

        when:
        def builders = sources.findAll {
            RepoSourceTree.code(it).contains(FACTORY_CALL)
        }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        then:
        builders == [SETTINGS_JUDGE, OWNER].sort()
    }

    // FR24: no test loads a contract schema file but through the owner
    def "FR24: no test source spells #shape outside its listed exemptions"() {
        given: 'every test source and the shared test fixtures, comments removed'
        def sources = testTree()

        expect: 'the scan really reached the tree, the round-trip spec included'
        sources.size() >= RepoSourceTree.KNOWN_TEST_SOURCES
        sources.collect { RepoSourceTree.relative(it) }.contains(ROUND_TRIP)

        when:
        def spellers = sources.findAll {
            pattern.matcher(RepoSourceTree.code(it)).find()
        }
        .collect { RepoSourceTree.relative(it) }
        .findAll { !EXEMPT[it]?.containsKey(shape) }

        then:
        spellers == []

        where:
        shape << TEST_SHAPES.keySet().toList()
        pattern = TEST_SHAPES[shape]
    }

    // FR1, FR24: the consumer the row names takes its schemas from the owner
    def "FR1, FR24: the round-trip spec judges through WireSchemas and reads the files' examples from it"() {
        given:
        def code = RepoSourceTree.code(RepoSourceTree.repoRoot().resolve(ROUND_TRIP).toFile())

        expect:
        code.contains('WireSchemas.judge(')
        code.contains('WireSchemas.document(')
    }

    // The exemptions are asserted reached: a listed file that no longer spells its shape is a stale row
    def "every listed exemption still spells its shape: #path"() {
        given:
        def file = RepoSourceTree.repoRoot().resolve(path).toFile()

        expect:
        file.isFile()
        shapes.keySet().every {
            TEST_SHAPES[it].matcher(RepoSourceTree.code(file)).find()
        }

        where:
        path << EXEMPT.keySet().toList()
        shapes = EXEMPT[path]
    }

    // The detector is the gate: a seeded load in code is found, a mention in a comment or an $id is not
    def "the detector: #description"() {
        expect:
        TEST_SHAPES.values().any {
            it.matcher(RepoSourceTree.codeOnly(line)).find()
        } == detected

        where:
        description | line || detected
        'a resource load of one file' | 'getResourceAsStream("/schemas/executor-result.schema.json")' || true
        'an interpolated load' | 'getResourceAsStream("/schemas/${file}")' || true
        'a mapped prefix' | "mapPrefix(ID_BASE, 'classpath:schemas/')" || true
        'a factory of its own' | 'JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)' || true
        'the schema $id base' | "ID_BASE = 'https://github.com/oinsio/gnomish-factory/schemas/executor-contract/1/'" || false
        'a file name alone' | "kind.schemaFile() == 'executor-result.schema.json'" || false
        'a comment mention' | '// read /schemas/executor-result.schema.json through the owner' || false
    }

    /** Every test tree and the shared fixtures, without this gate, which spells the shapes it bans. */
    private static List<File> testTree() {
        (RepoSourceTree.testSources() + RepoSourceTree.productionSources {
            it.startsWith('test-fixtures/')
        })
        .findAll { RepoSourceTree.relative(it) != SELF }
    }
}
