package com.github.oinsio.gnomish.adapter.git.state

import com.github.oinsio.gnomish.app.port.git.UnsupportedStateFileVersionException
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.DenialIdentity
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.Participants
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.ToolUsage
import com.github.oinsio.gnomish.domain.engine.UsageFold
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import com.github.oinsio.gnomish.domain.engine.fake.Snapshots
import com.github.oinsio.gnomish.domain.engine.fake.Unpriced
import com.github.oinsio.gnomish.executorcontract.usagewire.UsageCostDto
import com.github.oinsio.gnomish.executorcontract.usagewire.UsageSnapshotDto
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * Verifies {@link StateJsonMapper} against the {@code state.json} v1 contract:
 * mapping FROM a domain {@link TaskState} through the DTO tree and back to an
 * equal {@code TaskState}, covering every sealed-type branch (all 4
 * AttemptRecord.Result values, all 3 Verdict kinds, both Position variants),
 * plus empty/populated tokensByModel/tools/checks and multiple attempts.
 *
 * FR3, FR4: state.json v1 contract, DTOs and mappers.
 */
class StateJsonMapperSpec extends Specification {

    def startedAt = Instant.parse("2026-07-18T09:00:00Z")

    // FR15, FR17 of define-executor-contract (F66 of tighten-spec-contract): the written document's
    //     field list is the scenario's — the identity and one usage list per attempt, no per-kind
    //     usage field, totals and the executor version pin at the top level
    // Scenario: git/git-task-persistence / state.json carries the position and the current attempts
    def "toDto writes version, position, attemptsUsed, identified attempts with one usage list, totals and the pin"() {
        given:
        def usage = new ExecutorUsage(Duration.ofSeconds(3), [
            new ToolUsage("bash", 1, Duration.ofMillis(40))
        ],
        Unpriced.of(["model-a": new TokenUsage(10, 20, 0, 0)]))
        def participants = AttemptRecords.executed(usage) + [
            Participants.verdict('agent-cli', 0, Unpriced.of(["model-a": new TokenUsage(1, 2, 0, 0)]))
        ]
        def record = AttemptRecords.of(0, AttemptRecord.Result.PASSED, startedAt, [], participants, [], Stop.none())
        def pin = new ExecutorVersions(acme: UntrustedText.subprocess('1.4.0'))
        def state = new TaskState(new Position.AtStage("implement"), 2, [record], UsageFold.foldAll([record]), pin)

        when:
        def tree = TaskStateJson.mapper().readTree(TaskStateJson.mapper().writeValueAsString(StateJsonMapper.toDto(state)))
        def attempt = tree.get('attempts').get(0)

        then: 'the top level carries the position, the attempts, totals and the pin (the denial cursor is its own requirement)'
        tree.fieldNames().toList() - 'egressCursor' == [
            'version',
            'position',
            'attemptsUsed',
            'attempts',
            'totals',
            'executorVersions'
        ]
        tree.get('version').intValue() == 1
        tree.get('position').get('stage').textValue() == 'implement'
        tree.get('attemptsUsed').intValue() == 2
        tree.get('executorVersions').get('acme').textValue() == '1.4.0'

        and: 'each attempt carries its identity and one usage list, no executorUsage or judgeUsage'
        attempt.fieldNames().toList().toSet() ==
                [
                    'taskId',
                    'stage',
                    'roundToken',
                    'round',
                    'result',
                    'startedAt',
                    'stop',
                    'checks',
                    'denials',
                    'usage'
                ] as Set
        attempt.get('taskId').textValue() == AttemptRecords.TASK_ID
        attempt.get('stage').textValue() == AttemptRecords.STAGE
        attempt.get('roundToken').textValue() == AttemptRecords.TOKEN.commit()

        and: 'the usage list holds one participant entry per participant, by role'
        attempt.get('usage').collect {
            it.get('role').textValue()
        } == ['transform', 'verdict']
        attempt.get('usage').get(1).get('vote').intValue() == 0

        and: 'totals is the usage snapshot: role x model counts without cost, the cost a lower bound, the position'
        def totals = tree.get('totals')
        totals.fieldNames().toList() == [
            'byRole',
            'cost',
            'rounds',
            'through'
        ]
        totals.get('byRole').fieldNames().toList() == ['transform', 'verdict']
        totals.get('byRole').get('transform').get('model-a').fieldNames().toList() == [
            'input',
            'output',
            'cacheCreation',
            'cacheRead'
        ]
        totals.get('byRole').get('verdict').get('model-a').get('input').intValue() == 1
        totals.get('cost').fieldNames().toList() == [
            'priced',
            'unpricedTokens',
            'complete'
        ]
        !totals.get('cost').get('complete').booleanValue()
        totals.get('cost').get('unpricedTokens').get('transform').get('model-a').get('output').intValue() == 20
        totals.get('rounds').intValue() == 1
        totals.get('through').textValue() == AttemptRecords.TOKEN.commit()
    }

    def "toDto maps PipelineEnd position"() {
        given:
        def state = new TaskState(new Position.PipelineEnd(), 0, [], UsageSnapshot.EMPTY, ExecutorVersions.none())

        when:
        def dto = StateJsonMapper.toDto(state)

        then:
        dto.position() == new StatePositionDto.PipelineEnd("pipelineEnd")
    }

    def "toDto maps every AttemptRecord.Result to its lowerCamel discriminator"() {
        given:
        def record = AttemptRecords.of(0, result, startedAt, [], ExecutorUsage.none(), [], Stop.none())
        def state = new TaskState(new Position.AtStage("implement"), 0, [record], UsageSnapshot.EMPTY, ExecutorVersions.none())

        when:
        def dto = StateJsonMapper.toDto(state)

        then:
        dto.attempts()[0].result() == expected

        where:
        result | expected
        AttemptRecord.Result.PASSED | "passed"
        AttemptRecord.Result.QUALITY_FAILURE | "qualityFailure"
        AttemptRecord.Result.CANNOT_VERIFY | "cannotVerify"
        AttemptRecord.Result.DECISION_NEEDED | "decisionNeeded"
        AttemptRecord.Result.BUDGET_EXHAUSTED | "budgetExhausted"
    }

    // FR7 of define-executor-contract (testing.md wire-vocabulary rule): every result token reads
    //     back as itself, iterating the whole constant set
    def "every AttemptRecord.Result round-trips through its state.json token: #result"() {
        given:
        def record = AttemptRecords.of(0, result, startedAt, [], ExecutorUsage.none(), [], Stop.none())
        def state = new TaskState(new Position.AtStage("implement"), 0, [record], UsageSnapshot.EMPTY, ExecutorVersions.none())

        expect:
        StateJsonMapper.fromDto(StateJsonMapper.toDto(state)).attempts()[0].result() == result

        where:
        result << AttemptRecord.Result.values().toList()
    }

    def "toDto flattens every Verdict kind onto StateCheckDto"() {
        given:
        def check = new CheckResult(new CheckRef(0, UntrustedText.manifest("command:./gradlew test")), verdict, Duration.ofMillis(500))
        def record = AttemptRecords.of(0, AttemptRecord.Result.PASSED, startedAt, [check], ExecutorUsage.none(), [], Stop.none())
        def state = new TaskState(new Position.AtStage("implement"), 0, [record], UsageSnapshot.EMPTY, ExecutorVersions.none())

        when:
        def dto = StateJsonMapper.toDto(state).attempts()[0].checks()[0]

        then:
        dto.ref() == "command:./gradlew test"
        dto.verdict() == expectedVerdict
        dto.findings() == expectedFindings
        dto.durationMillis() == 500
        dto.reason() == expectedReason
        dto.details() == expectedDetails
        dto.runUrl() == expectedRunUrl

        where:
        verdict | expectedVerdict | expectedFindings | expectedReason | expectedDetails | expectedRunUrl
        new Verdict.Pass() | "pass" | [] | null | null | null
        new Verdict.Pass("https://ci.example/runs/42") | "pass" | [] | null | null | "https://ci.example/runs/42"
        new Verdict.Fail([
            new Finding("bad", "file.txt:12", "trace")
        ]) | "fail" | [
            new StateFindingDto("bad", "file.txt:12", "trace")
        ] | null | null | null
        new Verdict.Fail([]) | "fail" | [] | null | null | null
        new Verdict.CannotVerify(UntrustedText.subprocess("timeout"), UntrustedText.subprocess("network blip")) | "cannotVerify" | [] | "timeout" | "network blip" | null
    }

    // FR15, FR17 of define-executor-contract: every field of the scenario's list reads back — the
    //     identity, the participant list and the pin included
    // Scenario: git/git-task-persistence / state.json carries the position and the current attempts
    def "round-trip: full TaskState with multiple attempts, checks, participants and the pin survives toDto/fromDto"() {
        given:
        def tokens = ["claude-sonnet": new TokenUsage(100, 50, 10, 5)]
        def usage = new ExecutorUsage(Duration.ofSeconds(30), [
            new ToolUsage("bash", 3, Duration.ofMillis(1200))
        ], Unpriced.of(tokens))
        def votes = [
            Participants.verdict('agent-cli', 0, Unpriced.of(tokens)),
            Participants.verdict('agent-cli', 1, [:])
        ]
        def check1 = new CheckResult(new CheckRef(0, UntrustedText.manifest("command:./gradlew test")), new Verdict.Pass(), Duration.ofMillis(200))
        def check2 = new CheckResult(
                new CheckRef(0, UntrustedText.manifest("judge:acceptance.md")),
                new Verdict.Fail([
                    new Finding("missing case", "Foo.java:10", null)
                ]),
                Duration.ofMillis(300))
        def attempt0 = AttemptRecords.of(0, AttemptRecord.Result.QUALITY_FAILURE, startedAt, [check2], usage, [], Stop.none())
        def attempt1 = AttemptRecords.of(1, AttemptRecord.Result.PASSED, startedAt.plusSeconds(60), [check1],
        AttemptRecords.executed(usage) + votes, [], Stop.none())
        def state = new TaskState(new Position.AtStage("implement"), 1, [attempt0, attempt1], Snapshots.executed(tokens),
        new ExecutorVersions(acme: UntrustedText.subprocess('1.4.0')))

        when:
        def dto = StateJsonMapper.toDto(state)
        def rebuilt = StateJsonMapper.fromDto(dto)

        then: 'write -> read -> write is stable: every value the wire format owes survives'
        // Compared at the DTO level, not against `state` itself. The reader re-mints each field it
        // lifts out of the document with the branch-document provenance (design D3 of
        // type-untrusted-text) and the carrier's equality includes provenance, so a state read
        // back is equal to the one written in text but not in provenance — deliberately: what
        // matters about a value read off a branch is that some instance wrote it.
        StateJsonMapper.toDto(rebuilt) == dto

        and: 'the identity, the participant list and the pin are among the values that survived'
        rebuilt.attempts().collect {
            [
                it.taskId(),
                it.stage(),
                it.roundToken()
            ]
        } ==
        [
            [
                AttemptRecords.TASK_ID,
                AttemptRecords.STAGE,
                AttemptRecords.TOKEN
            ]
        ] * 2
        rebuilt.attempts().collect { it.usage().size() } == [1, 3]
        rebuilt.executorVersions().of('acme').get().raw() == '1.4.0'
        rebuilt.totals() == state.totals()
    }

    def "round-trip: empty tokensByModel, empty tools, empty checks, no attempts survives"() {
        given:
        def state = TaskState.atStageStart("implement")

        when:
        def rebuilt = StateJsonMapper.fromDto(StateJsonMapper.toDto(state))

        then:
        rebuilt == state
    }

    def "round-trip: PipelineEnd position survives"() {
        given:
        def state = new TaskState(new Position.PipelineEnd(), 0, [], UsageSnapshot.EMPTY, ExecutorVersions.none())

        when:
        def rebuilt = StateJsonMapper.fromDto(StateJsonMapper.toDto(state))

        then:
        rebuilt == state
    }

    // FR1, design D3 of type-untrusted-text: the wire format is unchanged — the writer puts the
    //     check's own words in raw and the reader lifts them back out — but what comes back is a
    //     BRANCH_DOCUMENT carrier whatever medium first produced it, because the branch is what the
    //     next instance read it from. So the round trip is byte-identical and provenance-normalizing.
    // Scenario: core/untrusted-text / A machine writer is an exit
    def "round-trip: CannotVerify verdict survives with reason and details, re-minted as branch text"() {
        given: 'a verdict whose words were captured from a subprocess'
        def check = new CheckResult(new CheckRef(0, UntrustedText.manifest("external:ci")),
                new Verdict.CannotVerify(UntrustedText.subprocess("timeout"), UntrustedText.subprocess("poll exceeded")),
                Duration.ofMillis(1000))
        def record = AttemptRecords.of(0, AttemptRecord.Result.CANNOT_VERIFY, startedAt, [check], ExecutorUsage.none(), [], Stop.none())
        def state = new TaskState(new Position.AtStage("verify"), 0, [record], UsageSnapshot.EMPTY, ExecutorVersions.none())

        when:
        def rebuilt = StateJsonMapper.fromDto(StateJsonMapper.toDto(state))

        then: 'every word survives, now carried as what it is on the way back: a branch document\'s'
        def verdict = rebuilt.attempts()[0].checkResults()[0].verdict() as Verdict.CannotVerify
        verdict.reason() == UntrustedText.branchDocument("timeout")
        verdict.details() == UntrustedText.branchDocument("poll exceeded")

        and: 'and the rest of the state is unchanged'
        rebuilt.position() == state.position()
        rebuilt.attempts()[0].result() == state.attempts()[0].result()
    }

    def "round-trip: DecisionNeeded round with no checks survives"() {
        given:
        def record = AttemptRecords.of(0, AttemptRecord.Result.DECISION_NEEDED, startedAt, [], ExecutorUsage.none(), [], Stop.none())
        def state = new TaskState(new Position.AtStage("implement"), 0, [record], UsageSnapshot.EMPTY, ExecutorVersions.none())

        when:
        def rebuilt = StateJsonMapper.fromDto(StateJsonMapper.toDto(state))

        then:
        rebuilt == state
    }

    // FR7 of define-executor-contract: an executor-reported failed/quality round is recorded as a
    //     QUALITY_FAILURE whose one check result carries the executor's findings under the
    //     `executor:<stage>` label (index 0, the placeholder this file reads every index back as).
    //     It survives the document unchanged, so a resumed run feeds the same findings forward.
    def "round-trip via JSON: an executor-reported quality failure round survives with its findings"() {
        given:
        def mapper = TaskStateJson.mapper()
        def executorCheck = new CheckResult(new CheckRef(0, UntrustedText.manifest("executor:build")),
                new Verdict.Fail([
                    new Finding("the migration drops a column", "V3.sql:12", "irreversible")
                ]), Duration.ZERO)
        def record = AttemptRecords.of(0, AttemptRecord.Result.QUALITY_FAILURE, startedAt, [executorCheck],
        new ExecutorUsage(Duration.ofSeconds(3), [], Unpriced.of(["model-a": new TokenUsage(5, 6, 0, 0)])),
        [], Stop.none())
        def state = new TaskState(new Position.AtStage("build"), 1, [record], UsageSnapshot.EMPTY, ExecutorVersions.none())

        when:
        def json = mapper.writeValueAsString(StateJsonMapper.toDto(state))
        def rebuilt = StateJsonMapper.fromDto(mapper.readValue(json, StateJsonDto))

        then:
        rebuilt == state
        rebuilt.attemptsUsed() == 1
        rebuilt.attempts()[0].checkResults() == [executorCheck]
    }

    def "round-trip via JSON: serialize then deserialize a full StateJsonDto tree is equal"() {
        given:
        def mapper = TaskStateJson.mapper()
        def state = new TaskState(
                new Position.AtStage("implement"),
                1,
                [
                    AttemptRecords.of(
                            0,
                            AttemptRecord.Result.QUALITY_FAILURE,
                            startedAt,
                            [
                                new CheckResult(new CheckRef(0, UntrustedText.manifest("command:./gradlew test")), new Verdict.Fail([
                                    new Finding("bad", null, null)
                                ]), Duration.ofMillis(400))
                            ],
                            AttemptRecords.executed(new ExecutorUsage(Duration.ofSeconds(5), [], Unpriced.of(["model-a": new TokenUsage(10, 20, 0, 0)]))) +
                            [
                                Participants.verdict('agent-cli', 0, Unpriced.of(["model-a": new TokenUsage(1, 2, 0, 0)]))
                            ],
                            [], Stop.none())
                ], Snapshots.executed(["model-a": new TokenUsage(10, 20, 0, 0)]), ExecutorVersions.none())
        def dto = StateJsonMapper.toDto(state)

        when:
        def json = mapper.writeValueAsString(dto)
        def roundTripped = mapper.readValue(json, StateJsonDto)

        then:
        roundTripped == dto
        StateJsonMapper.toDto(StateJsonMapper.fromDto(roundTripped)) == dto
    }

    def "round-trip via JSON: a snapshot with no token and nothing priced survives serialize/deserialize"() {
        given:
        def mapper = TaskStateJson.mapper()
        def dto = new StateJsonDto(1, new StatePositionDto.AtStage("atStage", "implement"), 0, [],
        new UsageSnapshotDto([:], new UsageCostDto(BigDecimal.ZERO, [:], true), 0, null), null, null)

        when:
        def json = mapper.writeValueAsString(dto)
        def roundTripped = mapper.readValue(json, StateJsonDto)

        then:
        roundTripped == dto
        json.contains('"totals":{"byRole":{},"cost":{"priced":0,"unpricedTokens":{},"complete":true},"rounds":0}')
    }

    // FR17 of define-executor-contract: a recorded round is never read as one that consumed nothing
    def "an attempt with no usage entry is refused on read"() {
        given:
        def json = '{"version":1,"position":{"type":"atStage","stage":"build"},"attemptsUsed":0,"attempts":[' +
                '{"taskId":"T-1","stage":"build","roundToken":null,"round":3,"result":"passed",' +
                '"startedAt":"2026-07-18T09:00:00Z","stop":{"type":"none"},"checks":[],"denials":[]}],' +
                '"totals":{"byRole":{},"cost":{"priced":0,"unpricedTokens":{},"complete":true},"rounds":0}}'

        when:
        StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(json)))

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message == 'state.json attempt 3 carries no usage entry'
    }

    def "round-trip via JSON: unknown fields in the JSON are ignored on read"() {
        given:
        def mapper = TaskStateJson.mapper()
        def json = '''
        {
          "version": 1,
          "position": {"type": "atStage", "stage": "implement"},
          "attemptsUsed": 0,
          "attempts": [],
          "totals": {"byRole": {}, "cost": {"priced": 0, "unpricedTokens": {}, "complete": true}, "rounds": 0},
          "someFutureField": "ignored"
        }
        '''

        when:
        def dto = mapper.readValue(json, StateJsonDto)

        then:
        dto.version() == 1
        dto.attemptsUsed() == 0
    }

    def "readDto parses a version-1 document, tolerating unknown fields"() {
        given:
        def json = '''
        {
          "version": 1,
          "position": {"type": "atStage", "stage": "implement"},
          "attemptsUsed": 0,
          "attempts": [],
          "totals": {"byRole": {}, "cost": {"priced": 0, "unpricedTokens": {}, "complete": true}, "rounds": 0},
          "someFutureField": "ignored"
        }
        '''

        when:
        def dto = StateJsonMapper.readDto(UntrustedText.branchDocument(json))

        then:
        dto.version() == 1
        dto.attemptsUsed() == 0
    }

    // FR5 of fix-denial-report-attachment: the cursor delimiting an attempt's denials is committed
    //     with that attempt, so a resuming instance continues the delta instead of replaying the
    //     guard container's whole surviving log onto its first round
    // Scenario: git/git-task-persistence / The cursor is committed with the attempt it delimits
    def "FR5: toDto carries the environment's denial cursor into the document"() {
        given:
        def state = TaskState.atStageStart("implement")
        def cursor = new EgressCursorDto("sha256:guard-container", "2026-08-19T10:00:00.000000001Z")

        when:
        def json = TaskStateJson.mapper().writeValueAsString(StateJsonMapper.toDto(state, cursor))

        then:
        StateJsonMapper.readDto(UntrustedText.branchDocument(json)).egressCursor() == cursor
    }

    // Scenario: git/git-task-persistence / A writer with no denial source records no cursor
    def "FR5: a writer with no denial source records no cursor"() {
        expect:
        StateJsonMapper.toDto(TaskState.atStageStart("implement")).egressCursor() == null
    }

    // Scenario: git/git-task-persistence / A state file written before the cursor existed stays readable
    def "FR5: a state file written before the cursor existed still parses, with no cursor"() {
        given: 'a pre-cursor document — the field is additive under contract v1'
        def json = '''
        {
          "version": 1,
          "position": {"type": "atStage", "stage": "implement"},
          "attemptsUsed": 0,
          "attempts": [],
          "totals": {"byRole": {}, "cost": {"priced": 0, "unpricedTokens": {}, "complete": true}, "rounds": 0}
        }
        '''

        expect:
        StateJsonMapper.readDto(UntrustedText.branchDocument(json)).egressCursor() == null
    }

    // Scenario: git/git-task-persistence / Unknown version refuses resume
    def "readDto refuses an unsupported version before attempting to bind the DTO shape"() {
        given:
        // shape is incompatible with StateJsonDto (no position/attempts/totals) to
        // prove the version gate runs before any record binding is attempted
        def json = '{"version": 2, "somethingElseEntirely": true}'

        when:
        StateJsonMapper.readDto(UntrustedText.branchDocument(json))

        then:
        def e = thrown(UnsupportedStateFileVersionException)
        e.message == "state.json: unsupported version 2 (supported: 1)"
        e.fileName() == "state.json"
        e.foundVersion() == 2
        e.supportedVersion() == 1
    }

    def "readDto refuses a document with no version field"() {
        given:
        def json = '{"attemptsUsed": 0}'

        when:
        StateJsonMapper.readDto(UntrustedText.branchDocument(json))

        then:
        def e = thrown(UnsupportedStateFileVersionException)
        e.message == "state.json: missing (supported: 1)"
        e.foundVersion() == -1
    }

    def "toDto maps totals independently from attempts' per-round usage"() {
        given:
        def roundUsage = new ExecutorUsage(Duration.ofSeconds(1), [], [:])
        def totals = Snapshots.executed(["model-a": new TokenUsage(1, 1, 0, 0)])
        def record = AttemptRecords.of(0, AttemptRecord.Result.PASSED, startedAt, [], roundUsage, [], Stop.none())
        def state = new TaskState(new Position.AtStage("implement"), 0, [record], totals, ExecutorVersions.none())

        when:
        def dto = StateJsonMapper.toDto(state)

        then:
        dto.totals().byRole() == ['transform': ['model-a': new com.github.oinsio.gnomish.executorcontract.ModelUsage(1, 1, 0, 0, null)]]
        dto.attempts()[0].usage()*.wallMillis() == [1000L]
    }

    // FR4, M1 of fix-denial-report-attachment: the scenario the pre-change contract could not
    //     hold — a PASSING attempt carrying a denial, separate from its (all-pass) checks
    // Scenario: git/git-task-persistence / Denials persist with the attempt
    def "FR4: a passing attempt's denials survive the round-trip and serialize with the finding shape"() {
        given: 'a passing round whose environment denied one egress attempt'
        def denial = new Finding(
                "egress denied: paste.example.com:443", "paste.example.com:443/upload", "kind=http method=POST")
        def check = new CheckResult(new CheckRef(0, UntrustedText.manifest("command:./gradlew test")), new Verdict.Pass(), Duration.ofMillis(9))
        def record = AttemptRecords.of(
                0,
                AttemptRecord.Result.PASSED,
                startedAt,
                [check],
                ExecutorUsage.none(),
                [Denial.unidentified(denial)], Stop.none())
        def state = new TaskState(new Position.AtStage("implement"), 1, [record], UsageSnapshot.EMPTY, ExecutorVersions.none())

        when:
        def dto = StateJsonMapper.toDto(state)
        def json = TaskStateJson.mapper().writeValueAsString(dto)

        then: 'the denial rides its own field, in the same shape a check finding uses (NFR-S1: no body)'
        dto.attempts()[0].denials() == [
            new StateDenialDto(
            "egress denied: paste.example.com:443", "paste.example.com:443/upload", "kind=http method=POST", null)
        ]
        json.contains('"denials":[{"message":"egress denied: paste.example.com:443"')

        and: 'the attempt stayed passed, with its check untouched (FR2)'
        dto.attempts()[0].result() == "passed"
        dto.attempts()[0].checks()*.verdict() == ["pass"]

        and: 'the whole state rebuilds equal, denial included'
        StateJsonMapper.toDto(StateJsonMapper.fromDto(TaskStateJson.mapper().readValue(json, StateJsonDto))) ==
                StateJsonMapper.toDto(state)
    }

    // FR4, D5: additive under contract v1 — a state file written before the field existed
    //     must keep parsing, with denials read as empty rather than null
    // Scenario: git/git-task-persistence / Pre-existing state files stay readable
    // Scenario: observability/status-report / An attempt without the denials field reads as empty
    def "FR4: a state file written before denials existed still parses, with denials empty"() {
        given: 'a v1 attempt document with no denials field at all'
        def json = '''
        {
          "version": 1,
          "position": {"type": "atStage", "stage": "implement"},
          "attemptsUsed": 1,
          "attempts": [{
            "taskId": "T-1",
            "stage": "implement",
            "roundToken": "0123456789abcdef0123456789abcdef01234567",
            "round": 0,
            "result": "passed",
            "startedAt": "2026-07-18T09:00:00Z",
            "checks": [],
            "usage": [{"role": "transform", "executor": "agent-cli", "provenance": "absent", "byTool": [], "byModel": {}}]
          }],
          "totals": {"byRole": {}, "cost": {"priced": 0, "unpricedTokens": {}, "complete": true}, "rounds": 0}
        }
        '''

        when:
        def state = StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(json)))

        then: 'the document is readable and the absent field is an empty list, never null'
        state.attempts()[0].denials() == []
    }

    // FR7 of fix-denial-attribution-durability: the identity is what makes a re-read a merge
    //     rather than a doubled report, so it must survive the same round-trip the finding does
    // Scenario: git/git-task-persistence / A denial's identity is kept when known and when unknown
    // Scenario: git/git-task-persistence / Denials persist with the attempt
    def "FR7: an attempt denial's identity survives the state-file round-trip"() {
        given: 'a round whose denial the guard stamped with its own source and event time'
        def denial = new Denial(
                new Finding("egress denied: paste.example.com:443", "paste.example.com:443", "kind=connect"),
                new DenialIdentity('sha256:container-1', '2026-08-19T10:00:00.000000001Z'))
        def record = AttemptRecords.of(
                0, AttemptRecord.Result.PASSED, startedAt, [], ExecutorUsage.none(), [denial], Stop.none())
        def state = new TaskState(new Position.AtStage("implement"), 1, [record], UsageSnapshot.EMPTY, ExecutorVersions.none())

        when:
        def json = TaskStateJson.mapper().writeValueAsString(StateJsonMapper.toDto(state))

        then: 'the identity rides the denial entry, not the shared finding shape'
        json.contains('"identity":{"source":"sha256:container-1","at":"2026-08-19T10:00:00.000000001Z"}')

        and: 'and comes back identical, so the merge can recognize the event again'
        StateJsonMapper.fromDto(TaskStateJson.mapper().readValue(json, StateJsonDto)) == state
    }

    // FR7: additive under contract v1 — "unknown, keep": an entry written before identities
    //     existed matches nothing on a merge and is therefore re-read rather than dropped
    // Scenario: git/git-task-persistence / A denial's identity is kept when known and when unknown
    def "FR7: a denial entry written before identities existed reads as unidentified"() {
        given: 'a v1 attempt whose denial carries the finding fields only'
        def json = '''
        {
          "version": 1,
          "position": {"type": "atStage", "stage": "implement"},
          "attemptsUsed": 1,
          "attempts": [{
            "taskId": "T-1",
            "stage": "implement",
            "roundToken": "0123456789abcdef0123456789abcdef01234567",
            "round": 0,
            "result": "passed",
            "startedAt": "2026-07-18T09:00:00Z",
            "checks": [],
            "denials": [{"message": "egress denied: paste.example.com:443"}],
            "usage": [{"role": "transform", "executor": "agent-cli", "provenance": "absent", "byTool": [], "byModel": {}}]
          }],
          "totals": {"byRole": {}, "cost": {"priced": 0, "unpricedTokens": {}, "complete": true}, "rounds": 0}
        }
        '''

        when:
        def state = StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(json)))

        then:
        state.attempts()[0].denials()*.finding()*.message() == [
            'egress denied: paste.example.com:443'
        ]
        state.attempts()[0].denials()*.identity() == [null]
    }

    // FR13 of define-executor-contract (D11): the executor version pin survives state.json, in name order
    def "the executor version pin round-trips through state.json and re-mints as branch text"() {
        given:
        def mapper = TaskStateJson.mapper()
        def pin = new ExecutorVersions(zeta: UntrustedText.subprocess('3'), acme: UntrustedText.subprocess('1.4.0'))
        def state = new TaskState(new Position.AtStage('build'), 0, [], UsageSnapshot.EMPTY, pin)

        when:
        def json = mapper.writeValueAsString(StateJsonMapper.toDto(state))
        def readBack = StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(json)))

        then:
        json.contains('"executorVersions":{"acme":"1.4.0","zeta":"3"}')
        readBack.executorVersions() == pin
        readBack.executorVersions().of('acme').get().provenance() == UntrustedText.branchDocument('x').provenance()
    }

    // FR13 of define-executor-contract: a tip written before the pin existed reads as no pin
    def "a state file without executorVersions reads as no pin"() {
        given:
        def json = '{"version":1,"position":{"type":"atStage","stage":"build"},"attemptsUsed":0,"attempts":[],' +
                '"totals":{"byRole":{},"cost":{"priced":0,"unpricedTokens":{},"complete":true},"rounds":0}}'

        expect:
        StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(json))).executorVersions() ==
                ExecutorVersions.none()
    }

    // FR14 of define-executor-contract (design D13): the record names itself on the wire, and the
    //     reader takes the identity as written — the token back through the one parse
    // Scenario: git/git-task-persistence / An attempt record carries its identity
    def "FR14: the attempt's taskId, stage and roundToken are written and read back as recorded"() {
        given: 'a round whose stage is not the stage the position names'
        def token = RoundToken.of('9f1c2b7a4e6d8c0b1a2f3e4d5c6b7a8f9e0d1c2b')
        def record = AttemptRecords.identified('PROJ-7', 'implement', token, 0, AttemptRecord.Result.PASSED,
                startedAt, [], ExecutorUsage.none(), [], Stop.none())
        def state = new TaskState(new Position.AtStage('review'), 0, [record], UsageSnapshot.EMPTY, ExecutorVersions.none())

        when:
        def json = TaskStateJson.mapper().writeValueAsString(StateJsonMapper.toDto(state))
        def read = StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(json)))

        then: 'the wire carries the identity first, the stage the round\'s own'
        json.contains('"attempts":[{"taskId":"PROJ-7","stage":"implement","roundToken":"' + token.commit() + '","round":0')

        and: 'the reader rebuilds the identity as written, never from the position'
        read.attempts()[0].taskId() == 'PROJ-7'
        read.attempts()[0].stage() == 'implement'
        read.attempts()[0].roundToken() == token
        read == state
    }

    // FR14 of define-executor-contract: a round with no token (in-place) round-trips as none
    def "FR14: an attempt without a round token round-trips as none"() {
        given:
        def record = AttemptRecords.identified('PROJ-7', 'implement', null, 0, AttemptRecord.Result.PASSED,
                startedAt, [], ExecutorUsage.none(), [], Stop.none())
        def state = new TaskState(new Position.AtStage('implement'), 0, [record], UsageSnapshot.EMPTY, ExecutorVersions.none())

        expect:
        StateJsonMapper.fromDto(StateJsonMapper.toDto(state)).attempts()[0].roundToken() == null
    }

    // FR14 of define-executor-contract: a reader never infers a round's identity — a document
    //     written before the identity existed is refused, not filled in from the position
    // Scenario: pipeline/stage-engine / Round identity is written, never inferred
    def "FR14: an attempt without taskId or stage is refused rather than filled in from the position"() {
        given:
        def json = """
        {
          "version": 1,
          "position": {"type": "atStage", "stage": "implement"},
          "attemptsUsed": 0,
          "attempts": [{
            ${field}
            "round": 0,
            "result": "passed",
            "startedAt": "2026-07-18T09:00:00Z",
            "checks": [],
            "usage": [{"role": "transform", "executor": "agent-cli", "provenance": "absent", "byTool": [], "byModel": {}}]
          }],
          "totals": {"byRole": {}, "cost": {"priced": 0, "unpricedTokens": {}, "complete": true}, "rounds": 0}
        }
        """

        when:
        StateJsonMapper.fromDto(StateJsonMapper.readDto(UntrustedText.branchDocument(json)))

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains(missing)

        where:
        field | missing
        '"stage": "implement",' | 'taskId'
        '"taskId": "T-1",' | 'stage'
    }
}
