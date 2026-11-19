package com.github.oinsio.gnomish.adapter.check

import static com.github.oinsio.gnomish.adapter.check.FilesExistRequests.files

import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
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
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1 of migrate-verify-to-executor-contract: the {@code files-exist} built-in's describe — role
 * {@code verdict} alone, the settings schema of its path list, no credential, egress or poll, usage
 * reported — and the runs it refuses: a role other than {@code verdict}, and any poll. The schema and
 * the run's own settings reading accept exactly the same settings.
 */
class FilesExistExecutorDescribeSpec extends Specification {

    @TempDir
    Path tempDir

    private FilesExistExecutor executor() {
        new FilesExistExecutor(new FilesExistCheckRunner(), new DirectoryWorkspace(tempDir))
    }

    def "FR1: the describe declares a verdict-only built-in with no credential, egress or poll"() {
        when:
        def describe = executor().describe()

        then:
        FilesExistExecutor.NAME == 'files-exist'
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

    def "FR1: the settings schema and the run accept exactly the same settings"() {
        given:
        def schema = executor().describe().settingsSchema()

        when:
        def judged = SettingsSchema.judge(schema, settings)
        def ran = executor().start(FilesExistRequests.verdict(settings))

        then: 'the preflight and the run agree: a refused setting never reaches a run that would accept it'
        (judged instanceof SettingsJudgement.Accepted) == accepted
        (ran.status() == Status.COMPLETED) == accepted

        where:
        settings || accepted
        [files: []] || true
        [files: ['a.txt']] || true
        [files: ['x'] * 1000] || true
        [files: ['x'] * 1001] || true
        [files: ['x'] * 5000] || true
        [files: ['a.txt'], other: 1] || true
        [:] || false
        [files: 'a.txt'] || false
        [files: ['a.txt', 42]] || false
        [files: ['a.txt', null]] || false
    }

    def "FR1: every describe hands out a schema of its own"() {
        given:
        def executor = executor()

        expect:
        !executor.describe().settingsSchema().is(executor.describe().settingsSchema())
    }

    def "NFR-C1: a run reports a known zero of model use"() {
        when:
        def usage = executor().start(files(request)).usage()

        then:
        usage.provenance() == UsageProvenance.REPORTED
        usage.models().isEmpty()
        usage.tools().isEmpty()
        usage.wallMillis() == null

        where:
        request << [
            ['missing.txt'],
            ['../escape']
        ]
    }

    def "FR1: a run in another role is failed/infrastructure naming the role, and nothing is judged"() {
        given:
        def request = new ExecutorRequest(Role.TRANSFORM, new Workspace('.'), new Policy.WorkspaceWrite(),
                RoleInputs.transform('make it'), ExecutorContractJson.settingsTree([files: ['../escape']]),
                Bounds.none(), [], null)

        when:
        def result = executor().start(request)

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw().contains('verdict runs only')
        result.reason().raw().contains('transform')
        !result.reason().raw().contains('escape')
    }

    def "FR1: a poll is failed/infrastructure, since a run is never pending"() {
        when:
        def result = executor().poll(files(['a.txt']), new PendingKey('k'))

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw().contains('never pending')
        result.usage().provenance() == UsageProvenance.REPORTED
    }
}
