package com.github.oinsio.gnomish.domain.engine

import spock.lang.Specification

/**
 * TaskContext: the opaque task identity, its human description, and the
 * chronological human decisions carried through to executor and judge requests
 * unmodified (design D6). Contract: {@code taskId} is a non-blank opaque key;
 * {@code title} and {@code body} are non-null description; {@code decisions} is
 * an unmodifiable, defensively copied chronological list. Implements FR7 of
 * add-stage-engine.
 */
class TaskContextSpec extends Specification {

    // FR7: a task context exposes its id, description and decisions as constructed
    def "a task context exposes taskId, title, body and decisions as constructed"() {
        given: 'a chronological list of decisions'
        def decisions = [
            new Decision('use library X', 'design', 'alice', null),
            new Decision('skip the cache', 'build', 'bob', null),
        ]

        when: 'a task context is created'
        def context = new TaskContext('TASK-42', 'Add caching', 'Cache the results.', decisions)

        then: 'each component is exposed exactly as constructed'
        context.taskId() == 'TASK-42'
        context.title() == 'Add caching'
        context.body() == 'Cache the results.'
        context.decisions() == decisions
    }

    // FR7: decisions are copied on construction — later source mutation cannot leak in
    def "the decisions list is defensively copied from the source"() {
        given: 'a mutable source list'
        def source = [
            new Decision('first', null, null, null)
        ]

        when: 'a task context is created and the source is then mutated'
        def context = new TaskContext('TASK-1', 'Title', 'Body', source)
        source.add(new Decision('sneaked in', null, null, null))

        then: 'the context keeps its original single decision'
        context.decisions().size() == 1
        context.decisions()[0].body() == 'first'
    }

    // FR7: the exposed decisions list is unmodifiable — no one edits it in place
    def "the exposed decisions list is unmodifiable"() {
        given: 'a task context'
        def context = new TaskContext('TASK-1', 'Title', 'Body', [
            new Decision('a', null, null, null)
        ])

        when: 'a caller tries to add a decision'
        context.decisions().add(new Decision('b', null, null, null))

        then: 'the modification is rejected'
        thrown(UnsupportedOperationException)
    }

    // FR7: a task may carry no decisions yet — an empty list is valid
    def "a task context accepts an empty decisions list"() {
        when: 'a task context is created with no decisions'
        def context = new TaskContext('TASK-1', 'Title', 'Body', [])

        then: 'the decisions list is empty'
        context.decisions().isEmpty()
    }

    // FR7: taskId is the opaque key — a blank key is meaningless and rejected
    def "blank taskId is rejected with the component name in the message"() {
        when: 'a task context is created with a blank taskId'
        new TaskContext(taskId, 'Title', 'Body', [])

        then: 'construction fails and the message names the blank component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('TaskContext.taskId')

        where:
        taskId << ['', '   ', '\t', ' \n']
    }

    // FR7: title/body are description, may be empty text, but never null
    def "an empty title and body are accepted"() {
        when: 'a task context is created with empty description strings'
        def context = new TaskContext('TASK-1', '', '', [])

        then: 'the empty description is exposed as constructed'
        context.title() == ''
        context.body() == ''
    }

    // FR7: task contexts are values — equal content means equal contexts
    def "task contexts with the same components are equal values"() {
        expect: 'two independently constructed contexts with equal components are equal'
        new TaskContext('TASK-1', 'Title', 'Body', [
            new Decision('a', null, null, null)
        ]) ==
        new TaskContext('TASK-1', 'Title', 'Body', [
            new Decision('a', null, null, null)
        ])
    }
}
