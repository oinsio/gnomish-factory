package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Shared
import spock.lang.Specification

/**
 * Design D4 as amended 2026-11-21 and the D8 row "The question a decision answers" of
 * extract-agent-round-runner (task 2.5a; FR9, NFR-R2): a decision is built in one place. {@code
 * DecisionBuilder.answering} decides the stage it is scoped to and the question it records; the two
 * resume paths — {@code take}'s {@code ResumeDecisionCommit} and {@code run}'s {@code
 * EscalationResume} — call it, and only the branch document's reader, {@code TaskJsonMapper},
 * through its decision-entry mapper {@code TaskDecisionMapper}, rebuilds a decision as recorded. The {@code take} path hands the builder the outcome the tip
 * records ({@code bootstrap.outcome()}), never the carried-forward {@code lastEscalation}. The one
 * renderer of a decision is {@code BriefingSections.renderDecisions}, which the stage round prompt
 * (through {@code renderExecutorBriefing}) and the judge prompt reach.
 *
 * <p>A whole-tree text scan of the production Java, comments stripped, that asserts it reached every
 * listed file; a Groovy test fixture under {@code test-fixtures/src/main} that builds a decision as
 * data is outside the rule.
 *
 * <p>FR9, NFR-R2 of extract-agent-round-runner.
 */
class DecisionOwnerSpec extends Specification {

    private static final String ENGINE = 'domain/src/main/java/com/github/oinsio/gnomish/domain/engine/'
    private static final String APP = 'application/src/main/java/com/github/oinsio/gnomish/app/'
    private static final String BUILDER = ENGINE + 'DecisionBuilder.java'
    private static final String STATE = 'adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/state/'
    private static final String DOCUMENT = STATE + 'TaskJsonMapper.java'
    private static final String MAPPER = STATE + 'TaskDecisionMapper.java'
    private static final String TAKE = APP + 'ResumeDecisionCommit.java'
    private static final String RUN = APP + 'EscalationResume.java'
    private static final String TAKE_HOST = APP + 'TakeResumeRunner.java'
    private static final String TAKE_CONTAINER = APP + 'TakeContainerResumeRunner.java'
    private static final String RENDERER = 'adapters/src/main/java/com/github/oinsio/gnomish/adapter/briefing/BriefingSections.java'
    private static final String ROUND_PROMPT = 'adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/ExecutorPromptBuilder.java'
    private static final String JUDGE_PROMPT = 'adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/JudgePromptBuilder.java'

    @Shared
    List<File> sources = RepoSourceTree.productionSources()

    def "the scan reached the tree, and every listed file with it"() {
        expect:
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        sources.collect {
            RepoSourceTree.relative(it)
        }.containsAll([
            BUILDER,
            DOCUMENT,
            MAPPER,
            TAKE,
            RUN,
            TAKE_HOST,
            TAKE_CONTAINER,
            RENDERER,
            ROUND_PROMPT,
            JUDGE_PROMPT
        ])
    }

    def "FR9: '#form' is spelled in production only by #owners"() {
        when:
        def spellers = sources.findAll {
            it.name.endsWith('.java') && RepoSourceTree.code(it).contains(form)
        }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        then:
        spellers == owners.sort()

        where:
        form || owners
        // a decision is built by the one builder; the branch document's reader rebuilds it as recorded
        'new Decision(' || [BUILDER, MAPPER]
        // both resume paths call the builder, and nothing else does
        'DecisionBuilder.answering(' || [TAKE, RUN]
        // the take media reach it through ResumeDecisionCommit only
        'ResumeDecisionCommit.decisionFor(' || [TAKE_HOST, TAKE_CONTAINER]
        // the one renderer, reached by the round prompt through the briefing and by the judge prompt
        'renderDecisions(' || [RENDERER, JUDGE_PROMPT]
        'renderExecutorBriefing(' || [RENDERER, ROUND_PROMPT]
        // the decision entries are mapped for the one task.json reader and writer only
        'TaskDecisionMapper.' || [DOCUMENT]
    }

    def "NFR-R2: the take path builds the decision from the outcome the tip records, never from lastEscalation"() {
        expect:
        [TAKE_HOST, TAKE_CONTAINER].every { path ->
            def code = RepoSourceTree.code(sources.find {
                RepoSourceTree.relative(it) == path
            })
            code.replaceAll(/\s+/, ' ').contains('ResumeDecisionCommit.decisionFor( finalState, bootstrap.outcome(), text,')
        }
        !RepoSourceTree.code(sources.find {
            RepoSourceTree.relative(it) == TAKE
        }).contains('lastEscalation')
    }
}
