package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.executorcontract.Bounds
import com.github.oinsio.gnomish.executorcontract.Describe
import com.github.oinsio.gnomish.executorcontract.EnforcementLevel
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.SettingsJudgement
import com.github.oinsio.gnomish.executorcontract.SettingsSchema
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.UsageProvenance
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.pendingkey.PendingKey
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR5, NFR-C1 of migrate-verify-to-executor-contract: the {@code shell} built-in's describe — role
 * {@code verdict} alone, settings that must be empty, no credential, egress or poll, usage reported —
 * and the runs it refuses before any process starts: a role other than {@code verdict}, settings,
 * and any poll.
 */
class ShellExecutorDescribeSpec extends Specification {

    @TempDir
    Path tempDir

    /** An executor whose check would leave a marker in the workspace if it ever ran. */
    private ShellExecutor executor() {
        new ShellExecutor(new ShellCommandCheckRunner(new VirtualClock()), VerifyChecks.command('touch ran'),
                new DirectoryWorkspace(tempDir))
    }

    static ExecutorRequest verdict(Map<String, ?> settings = [:]) {
        new ExecutorRequest(Role.VERDICT, new Workspace('.'), new Policy.ReadOnly(), RoleInputs.verdict('touch ran'),
                ExecutorContractJson.settingsTree(settings), Bounds.none(), [], null)
    }

    def "FR5: the describe declares a verdict-only built-in with no credential, egress or poll"() {
        when:
        def describe = executor().describe()

        then:
        ShellExecutor.NAME == 'shell'
        describe.protocolVersion() == Describe.SPOKEN_PROTOCOL_VERSION
        describe.version().raw() == 'built-in'
        describe.roles() == [Role.VERDICT]
        describe.capabilities().isEmpty()
        describe.credentials().isEmpty()
        describe.egress().isEmpty()
        describe.configDirVariable() == null
        describe.policyDisablingFlags().isEmpty()
        describe.enforcementLevel() == EnforcementLevel.PROMPT_ONLY
        describe.reportsUsage()
        !describe.checkIdIsPinnedPath()
    }

    def "FR5: the settings schema and the run accept exactly the same settings"() {
        given:
        def schema = executor().describe().settingsSchema()

        when:
        def judged = SettingsSchema.judge(schema, settings)
        def ran = executor().start(verdict(settings))

        then: 'the preflight and the run agree: a refused setting never reaches a run that would accept it'
        (judged instanceof SettingsJudgement.Accepted) == accepted
        (ran.status() == Status.COMPLETED) == accepted

        and: 'a refused run started no process'
        Files.exists(tempDir.resolve('ran')) == accepted

        where:
        settings || accepted
        [:] || true
        [command: 'x'] || false
        [a: 1, b: 2] || false
    }

    def "FR5: a run given settings names how many it was given"() {
        when:
        def result = executor().start(verdict([a: 1, b: 2]))

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw() == 'shell takes no settings, and was given 2'
    }

    def "FR5: every describe hands out a schema of its own"() {
        given:
        def executor = executor()

        expect:
        !executor.describe().settingsSchema().is(executor.describe().settingsSchema())
    }

    def "FR5: a run in another role is failed/infrastructure naming the role, and no process starts"() {
        given:
        def request = new ExecutorRequest(Role.TRANSFORM, new Workspace('.'), new Policy.WorkspaceWrite(),
                RoleInputs.transform('make it'), ExecutorContractJson.settingsTree([:]), Bounds.none(), [], null)

        when:
        def result = executor().start(request)

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw() == 'shell answers verdict runs only, and was asked for a transform run'
        result.usage().provenance() == UsageProvenance.REPORTED
        !Files.exists(tempDir.resolve('ran'))
    }

    def "FR5: a poll is failed/infrastructure, since a run is never pending"() {
        when:
        def result = executor().poll(verdict(), new PendingKey('k'))

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw() == 'shell is never pending, so there is nothing to poll'
        result.usage().provenance() == UsageProvenance.REPORTED
        !Files.exists(tempDir.resolve('ran'))
    }
}
