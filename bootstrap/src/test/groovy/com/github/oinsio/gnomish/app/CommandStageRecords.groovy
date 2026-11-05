package com.github.oinsio.gnomish.app

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import java.nio.file.Path

/**
 * Reads the command-stage flow specs' evidence from the task branch (task 7.1 of
 * add-command-executor): each round's own commit — {@code gnomish: round <stage>#<n>} — its tree, and
 * the state file it carries, whose attempt history is the record every later round's request is
 * built from.
 */
final class CommandStageRecords {

    private static final ObjectMapper JSON = new ObjectMapper()

    private final BareGitRepoFixture git
    private final Path cloneDir
    private final String branch

    CommandStageRecords(BareGitRepoFixture git, Path cloneDir, String branch) {
        this.git = git
        this.cloneDir = cloneDir
        this.branch = branch
    }

    /** The commit of {@code stage}'s round {@code round}; exactly one exists. */
    String roundCommit(String stage, int round) {
        def commits = git.gitOutput(cloneDir, 'log', '--format=%H', '--grep', "^gnomish: round ${stage}#${round}\$", branch)
                .readLines().findAll { !it.isBlank() }
        assert commits.size() == 1
        commits[0]
    }

    /** The paths of the tree {@code stage}'s round {@code round} committed. */
    List<String> treeOf(String stage, int round) {
        git.gitOutput(cloneDir, 'ls-tree', '-r', '--name-only', roundCommit(stage, round)).readLines()
    }

    /** The record of {@code stage}'s round {@code round}, as the state file of the commit of round {@code at} holds it. */
    JsonNode attempt(String stage, int round, int at = round) {
        def state = JSON.readTree(stateOf(stage, at))
        def records = state.get('attempts').findAll {
            it.get('stage').asText() == stage && it.get('round').asInt() == round
        }
        assert records.size() == 1
        records[0]
    }

    /** The messages of the non-passing check results of that record. */
    List<String> failedFindings(String stage, int round, int at = round) {
        attempt(stage, round, at).get('checks')
                .findAll { it.get('verdict').asText() != 'pass' }
                .collectMany { it.get('findings')*.get('message')*.asText() }
    }

    /** The check refs of that record, in chain order. */
    List<String> checkRefs(String stage, int round, int at = round) {
        attempt(stage, round, at).get('checks')*.get('ref')*.asText()
    }

    /** The one participant of that record's round. */
    JsonNode participant(String stage, int round) {
        def usage = attempt(stage, round).get('usage')
        assert usage.size() == 1
        usage.get(0)
    }

    /** Whether {@code stage}'s round {@code round} is the built-in's: no model, no tool, its wall time recorded. */
    boolean isCommandRound(String stage, int round) {
        def entry = participant(stage, round)
        entry.get('executor').asText() == 'command' && entry.get('byModel').size() == 0 &&
                entry.get('byTool').size() == 0 && entry.has('wallMillis')
    }

    /** The whole state file of that round's commit, for a failing assertion's message. */
    String stateOf(String stage, int round) {
        git.gitOutput(cloneDir, 'show', "${roundCommit(stage, round)}:.gnomish-task/state.json")
    }
}
