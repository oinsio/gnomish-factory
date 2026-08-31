package com.github.oinsio.gnomish.adapter.tracker.github

import spock.lang.Specification

/**
 * GithubCommentIdentity (FR11, NFR-S1 of harden-task-branch-contract, design
 * D7): the hidden content identity keying the find-then-upsert primitive —
 * task plus intent, never the posting account, and carrying no hostname,
 * path, or credential material.
 *
 * FR11: two writes of the same intent for the same task share one identity.
 * NFR-S1: the identity carries only task identity and counters.
 */
class GithubCommentIdentitySpec extends Specification {

    private static GithubTaskId taskId(String host = '', int number = 42) {
        new GithubTaskId(host, 'acme', 'widgets', number)
    }

    def "of scopes the task part to owner/repo#number"() {
        when:
        def identity = GithubCommentIdentity.of(taskId(), 'park')

        then:
        identity.task() == 'acme/widgets#42'
        identity.intent() == 'park'
    }

    def "of drops the non-default host segment so no hostname reaches the wire (NFR-S1)"() {
        when:
        def identity = GithubCommentIdentity.of(taskId('ghe.acme.example'), 'finish')

        then:
        identity.task() == 'acme/widgets#42'
        !identity.task().contains('ghe.acme.example')
    }

    def "two writes of the same intent for the same task are one identity, a different task is not"() {
        expect:
        GithubCommentIdentity.of(taskId(), 'park') == GithubCommentIdentity.of(taskId(), 'park')
        GithubCommentIdentity.of(taskId(), 'park') != GithubCommentIdentity.of(taskId('', 43), 'park')
        GithubCommentIdentity.of(taskId(), 'park') != GithubCommentIdentity.of(taskId(), 'finish')
    }

    def "surrounding whitespace is stripped so a padded intent still matches"() {
        expect:
        new GithubCommentIdentity(' acme/widgets#42 ', ' abort#3 ') ==
                new GithubCommentIdentity('acme/widgets#42', 'abort#3')
    }

    def "a blank #field is rejected"() {
        when:
        new GithubCommentIdentity(task, intent)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(field)

        where:
        field | task | intent
        'task' | '' | 'park'
        'task' | '   ' | 'park'
        'task' | null | 'park'
        'intent' | 'acme/widgets#42' | ''
        'intent' | 'acme/widgets#42' | '  '
        'intent' | 'acme/widgets#42' | null
    }
}
