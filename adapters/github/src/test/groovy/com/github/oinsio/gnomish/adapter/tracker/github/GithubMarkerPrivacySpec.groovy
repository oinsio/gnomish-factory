package com.github.oinsio.gnomish.adapter.tracker.github

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import spock.lang.Specification

/**
 * What the hidden marker of every factory comment carries (NFR-S1): written by the real {@link
 * GithubTracker} for a task on a non-default host — the fixture GitHub of {@link
 * GithubKillWindowWorld}, whose canonical ids name their host — one kind per row of {@link
 * GithubMarkerWriteRows}, with and without a tenure's epoch stamp. The same markers carry the task's
 * repository-scoped identity, never the host-bearing canonical id.
 *
 * <p>Implements FR11, NFR-S1 of harden-task-branch-contract.
 */
class GithubMarkerPrivacySpec extends Specification {

    private static final Set<String> MARKER_FIELDS = [
        'kind',
        'instance',
        'at',
        'version',
        'reason',
        'task',
        'intent',
        'epoch'
    ] as Set

    private final GithubKillWindowWorld world = new GithubKillWindowWorld()

    def cleanup() {
        world.close()
    }

    // NFR-S1: the hidden marker carries task identity and counters, nothing about the host or the box.
    // Scenario: tracker/github-tracker / Markers carry no paths, hostnames, or credentials
    def "a #kind marker on a non-default host carries no path, hostname or credential (tenure #tenure)"() {
        given: 'a task whose canonical id names its host'
        world.open(seed)
        world.holdTenure(tenure)
        def host = new URI(world.ref().id().replaceFirst('^github:', 'http://')).host
        assert world.ref().id().contains(host)

        when:
        world.attempt(act)

        then:
        def hidden = GithubMarkerWriteRows.hiddenMarkers(world.issue())
        !hidden.isEmpty()
        hidden.every { json ->
            def fields = new ObjectMapper().readTree(json).fieldNames().toList() as Set
            MARKER_FIELDS.containsAll(fields) && !json.contains(host) && !json.contains('kill-window-token') &&
                    !(json =~ /\/(Users|home|tmp|private|var)\//) && !json.contains('\\\\')
        }


        where:
        [kind, seed, act, tenure] << (GithubMarkerWriteRows.rows() + [
            GithubMarkerWriteRows.claimRow()
        ])
        .collectMany { row ->
            [
                row + [null],
                row + [new ClaimEpoch(4242)]
            ]
        }
    }

    // FR16 of add-tracker-port: the scenario's structural-comment half. The markers carry the
    // repository-scoped owner/repo#N (GithubCommentIdentity, NFR-S1), never the canonical id, whose
    // host segment the scenario above bans from the hidden marker. The task.json, MDC and branch
    // halves are TakeTrackerRecordsSpec's.
    // F80: scenario text aligned to what this test asserts
    // Scenario: tracker/github-tracker / The canonical id flows unchanged into the records
    def "every #kind marker carries the task's repository-scoped identity, without the host"() {
        given:
        world.open(seed)
        def canonical = world.ref().id()
        def repositoryScoped = canonical.substring(canonical.indexOf(':') + 1).split('/').takeRight(2).join('/')

        when:
        world.attempt(act)

        then:
        def tasks = GithubMarkerWriteRows.hiddenMarkers(world.issue()).collect {
            new ObjectMapper().readTree(it).path('task').asText()
        }.findAll { it }
        !tasks.isEmpty()
        tasks.every { it == repositoryScoped }
        repositoryScoped != canonical

        where:
        [kind, seed, act] << GithubMarkerWriteRows.rows().take(1)
    }
}
