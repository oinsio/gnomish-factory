package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Shared
import spock.lang.Specification

/**
 * Design D12 of extract-agent-round-runner, the executor-block row (task 4.2; FR13, UX1): who does
 * the work is declared by one {@code executor:} block — a stage's and a {@code judge} check's —
 * bound by one wire record, {@code ExecutorDto}, and mapped by one function, {@code
 * StageDefinitionMapper.mapExecutor}, called for the stage's block and, through {@code
 * mapJudgeExecutor} beside it, for each judge's. So the
 * manifest keys {@code model} and {@code settings} of an executor are read in production by the one
 * block mapper only: no other wire record binds a {@code model} key, the judge's wire record
 * carries the block and nothing of its own, and the typed judge holds the block's type and no
 * {@code model} field. {@code BlankMechanismFields} is the one other reader of the block's {@code
 * model}, and asks only whether it was declared blank, which the mapping erases (design D3 of
 * add-command-executor).
 *
 * <p>A whole-tree text scan, comments stripped, that asserts it reached every listed file.
 *
 * <p>FR13, UX1 of extract-agent-round-runner.
 */
class ExecutorBlockOwnerSpec extends Specification {

    private static final String PIPELINE = 'adapters/src/main/java/com/github/oinsio/gnomish/adapter/pipeline/'
    /** The one wire record of an executor block. */
    private static final String BLOCK = PIPELINE + 'ExecutorDto.java'
    /** The one block mapper. */
    private static final String MAPPER = PIPELINE + 'StageDefinitionMapper.java'
    /** The wire check of a blank Mechanism field: reads the block's model for blankness only. */
    private static final String BLANK_FIELDS = PIPELINE + 'BlankMechanismFields.java'
    /** The stage's block is mapped here. */
    private static final String STAGE_CALLER = PIPELINE + 'PipelineMapper.java'
    /** Each judge check's block is mapped here. */
    private static final String JUDGE_CALLER = PIPELINE + 'VerifyCheckMapper.java'
    private static final String JUDGE_WIRE = PIPELINE + 'VerifyCheckDto.java'
    private static final String JUDGE_TYPED = 'domain/src/main/java/com/github/oinsio/gnomish/domain/pipeline/VerifyCheck.java'

    @Shared
    List<File> sources = RepoSourceTree.productionSources()

    def "the scan reached the tree, and every listed file with it"() {
        expect:
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        sources.collect { RepoSourceTree.relative(it) }.containsAll([
            BLOCK,
            MAPPER,
            BLANK_FIELDS,
            STAGE_CALLER,
            JUDGE_CALLER,
            JUDGE_WIRE,
            JUDGE_TYPED
        ])
    }

    // FR13: the block's model and settings keys are read by the one mapper
    def "FR13: among the readers of the block's wire record, '#form' is spelled only by #owners"() {
        when:
        def spellers = sources.findAll {
            def code = RepoSourceTree.code(it)
            code.contains('ExecutorDto') && code.contains(form)
        }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        then:
        spellers == owners.sort()

        where:
        form || owners
        '.settings()' || [MAPPER]
        // the blank-field wire check asks whether the key was declared blank, nothing more
        '.model()' || [MAPPER, BLANK_FIELDS]
    }

    // FR13: one record of the pipeline wire binds a model key, and one function maps the block
    def "FR13: #form is spelled only by #owners"() {
        when:
        def spellers = sources.findAll {
            RepoSourceTree.relative(it).startsWith(scope) && RepoSourceTree.code(it) =~ pattern
        }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        then:
        spellers == owners.sort()

        where:
        form | scope | pattern || owners
        'a wire record component named model, in the pipeline wire' | PIPELINE | /@Nullable\s+String\s+model\s*[,)]/ || [BLOCK]
        'the stage block mapping call, in production' | '' | /StageDefinitionMapper\.mapExecutor\(/ || [STAGE_CALLER]
        'the judge block mapping call, in production' | '' | /StageDefinitionMapper\.mapJudgeExecutor\(/ || [JUDGE_CALLER]
    }

    // FR13, UX1: the judge carries the block and nothing of its own, on the wire and typed
    def "FR13: the judge check's wire record and typed record each hold the criteria and the block only"() {
        expect:
        squeezed(JUDGE_WIRE).contains(
                'record Judge(@Nullable String criteriaFile, @Nullable ExecutorDto executor) implements VerifyCheckDto')
        squeezed(JUDGE_TYPED).contains(
                'record Judge(String criteriaFile, StageDefinition.Executor executor) implements VerifyCheck')
    }

    private String squeezed(String path) {
        RepoSourceTree.code(sources.find {
            RepoSourceTree.relative(it) == path
        }).replaceAll(/\s+/, ' ')
    }
}
