package com.github.oinsio.gnomish.adapter.git.state

import com.github.oinsio.gnomish.app.port.git.BasePin
import com.github.oinsio.gnomish.app.port.git.BaseRefKind
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import java.time.Instant
import spock.lang.Specification

/**
 * {@link TaskJsonMapper}'s {@code (ref, kind, rule)} base pin (FR7 of add-base-ref-resolution): a
 * separate file from {@code TaskJsonMapperSpec} — that file is already at this project's file-size
 * cap (`.claude/rules/process-invariants.md`) — covering the pin's own round-trip, its unpinned
 * (legacy or absent) shape, and its preservation across a rewrite.
 */
class TaskJsonMapperBasePinSpec extends Specification {

    def someContext = new TaskContext(
    "task-1", "Fix flaky test", "body text",
    [
        new Decision("patch in place", "plan", "operator", Instant.parse("2026-07-16T14:21:30Z"))
    ])
    def createdAt = Instant.parse("2026-07-18T09:00:00Z")
    def baseCommit = "abc123"

    // FR7: a pinned document's ref and rule round-trip through toDto's wire tokens and back
    // through fromDto onto TaskRecord unchanged.
    def "toDto/fromDto round-trip a pinned BasePin"() {
        given:
        def pin = new BasePin("origin/release", BaseRefKind.BRANCH, BaseRule.DESIGNATOR)

        when:
        def dto = TaskJsonMapper.toDto(someContext, baseCommit, createdAt, null, null, false, pin)

        then: "the wire document carries the ref and the rule's wire token"
        dto.baseRef() == "origin/release"
        dto.baseRule() == "designator"
        dto.baseKind() == "branch"

        when:
        def record = TaskJsonMapper.fromDto(dto)

        then: 'fromDto rebuilds the same pin'
        record.pin() == pin
    }

    // FR7, D7 (revised 2026-09-10): every kind constant survives the wire round-trip, and an
    // unrecognized token reads as "no kind" — the documented forward-compatibility arm, which
    // degrades to classifying the name at resume time exactly as a pre-kind pin does.
    def "every BaseRefKind round-trips through the wire token (#kind)"() {
        expect:
        BaseRefKind.fromWire((kind as BaseRefKind).wireValue()) == kind

        where:
        kind << BaseRefKind.values()
    }

    def "an unrecognized or absent baseKind token reads as no kind (#token)"() {
        expect:
        BaseRefKind.fromWire(token) == null

        where:
        token << [
            null,
            '',
            'worktree',
            'BRANCH'
        ]
    }

    // FR7, D7: the manual tier classifies nothing, so its pin carries a ref and a rule without a
    // kind — written as an absent field and read back absent.
    def "a pin with no kind writes and reads no baseKind"() {
        given:
        def pin = new BasePin("release/1.18", null, BaseRule.EXPLICIT_ARGUMENT)

        when:
        def dto = TaskJsonMapper.toDto(someContext, baseCommit, createdAt, null, null, false, pin)

        then:
        dto.baseKind() == null

        and:
        TaskJsonMapper.fromDto(dto).pin() == pin
    }

    // FR7: BasePin.UNPINNED produces a document with neither field set, and fromDto reads it back
    // as unpinned — never a guessed rule.
    def "toDto/fromDto round-trip BasePin.UNPINNED as null/null"() {
        when:
        def dto = TaskJsonMapper.toDto(someContext, baseCommit, createdAt, null, null, false, BasePin.UNPINNED)

        then:
        dto.baseRef() == null
        dto.baseRule() == null
        dto.baseKind() == null

        when:
        def record = TaskJsonMapper.fromDto(dto)

        then:
        record.pin() == BasePin.UNPINNED
    }

    // FR7: a task.json written before the pin existed carries baseCommit alone; it reads as
    // unpinned rather than inventing a rule, mirroring the egressCursor legacy-read test above it.
    def "readDto parses a task.json written before the base pin existed"() {
        given: 'a version-1 document with no baseRef/baseRule fields'
        def json = '''
        {
          "version": 1,
          "taskId": "task-1",
          "title": "Title",
          "body": "Body",
          "createdAt": "2026-07-18T09:00:00Z",
          "baseCommit": "abc123",
          "decisions": [],
          "outcome": null,
          "lastEscalation": null
        }
        '''

        when:
        def record = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(json))

        then: 'it parses, and the task reads as carrying no durable pin'
        record.baseCommit() == "abc123"
        record.pin() == BasePin.UNPINNED
    }

    // FR7: an unrecognized rule token — a pin written by a newer build — folds to UNKNOWN rather
    // than failing to parse, so an older build stays able to read the document.
    def "readDto folds an unrecognized baseRule token to UNKNOWN"() {
        given:
        def json = '''
        {
          "version": 1,
          "taskId": "task-1",
          "title": "Title",
          "body": "Body",
          "createdAt": "2026-07-18T09:00:00Z",
          "baseCommit": "abc123",
          "decisions": [],
          "outcome": null,
          "lastEscalation": null,
          "baseRef": "origin/release",
          "baseRule": "some-future-rule"
        }
        '''

        when:
        def record = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(json))

        then:
        record.pin() == new BasePin("origin/release", null, BaseRule.UNKNOWN)
    }

    // FR7, NFR-S3: the pinned ref is read back from a document another instance wrote and is the
    // name the resume narrow-fetches; a malformed one is refused at the read — naming the
    // document and the violated rule — so it never reaches a refspec (task 12.2).
    def "readDto refuses a task.json whose baseRef is not a well-formed ref name: '#baseRef'"() {
        given:
        def json = TaskStateJson.mapper().writeValueAsString([
            version: 1, taskId: 'task-1', title: 'Title', body: 'Body',
            createdAt: '2026-07-18T09:00:00Z', baseCommit: 'abc123', decisions: [],
            baseRef: baseRef, baseRule: 'designator', baseKind: 'branch'])

        when:
        TaskJsonMapper.readDto(json)

        then:
        def refused = thrown(MalformedStateFileException)
        refused.message.startsWith('task.json: baseRef ')
        refused.message.contains(rule)

        and: 'the offending text is neutralized before it can reach a log or a report'
        !refused.message.contains('\n')
        !refused.message.contains(Character.toString(27 as char))

        where:
        baseRef || rule
        'release/../../secrets' || "'..'"
        'main' + Character.toString(27 as char) || 'control characters'
        'a\nb' || 'control characters'
        'release/' || 'empty path component'
        '*' || "'*' is not allowed"
        // a leading '-' makes the name an option to the rev-parse/fetch that would receive it
        '-oops' || "must not start with '-'"
        '--upload-pack=/bin/sh' || "must not start with '-'"
    }

    // FR7: a rewrite (appendDecision/recordOutcome's own toDto call) that carries the read-back
    // pin forward preserves it unchanged — the "flows through identically" half of the contract.
    def "a rewrite built from the read-back pin preserves it unchanged"() {
        given: 'a pinned document, read back into a TaskRecord'
        def original = TaskJsonMapper.toDto(
                someContext, baseCommit, createdAt, null, null, false,
                new BasePin("origin/release", BaseRefKind.TAG, BaseRule.DESIGNATOR))
        def record = TaskJsonMapper.fromDto(original)

        when: 'a rewrite carries the read-back pin forward, as GitTaskRepository does'
        def rewritten = TaskJsonMapper.toDto(someContext, baseCommit, createdAt, null, null, false, record.pin())

        then:
        rewritten.baseRef() == original.baseRef()
        rewritten.baseRule() == original.baseRule()
        rewritten.baseKind() == original.baseKind()
    }
}
