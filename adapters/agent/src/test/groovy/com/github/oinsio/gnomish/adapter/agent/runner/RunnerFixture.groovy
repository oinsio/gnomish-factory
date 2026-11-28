package com.github.oinsio.gnomish.adapter.agent.runner

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ChildEnvValues
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import java.nio.file.Path

/**
 * One {@link AgentRoundRunner#start} with {@link FakeDialect}, whose CLI is a {@link FakeProcess}
 * handed to a host environment through its {@link ProcessLaunch}: inside the launch, before the fake is
 * handed back, {@code files} writes what the CLI would — the answer file at the path the factory named
 * and the stderr file — so no feature rests on a thread order. A {@code git} launch is the
 * working-copy check's, answered from {@link #views} in turn (a clean view when none are left). The
 * specs set the fields they vary.
 */
final class RunnerFixture {

    static final String NAME = 'fake'

    static final String CLEAN = '# branch.oid abc\u0000# branch.head gnomish/TASK-7\u0000'

    /** The credential source of a grant: FAKE_API_KEY and TRACKER_TOKEN resolve, nothing else does. */
    static final ChildEnvValues SECRETS = { String name ->
        Optional.ofNullable([FAKE_API_KEY: 'fake-secret', TRACKER_TOKEN: 'tracker-secret'][name])
    } as ChildEnvValues

    Path workspace
    Path stderrParent
    ProgramBounds bounds = ProgramBounds.DEFAULTS
    TimeEquipment time = VirtualTimeEquipment.on(new VirtualClock())
    StdoutCaps caps = StdoutCaps.DEFAULTS
    FakeDialect dialect = new FakeDialect()
    /** Another dialect the runner runs instead of {@link #dialect}, when set. */
    AgentDialect<?> otherDialect
    /** The open round's answer path the runner is bound to, or null for a fresh one per request. */
    String answerFile
    /** The names the run's allowlist declares, and so withholds from every child. */
    List<String> withheld = []
    AgentProgressListener listener
    final List<AgentProgressEvent> progress = []
    final List<Object> views = []
    final List<ProcessBuilder> agentLaunches = []
    final List<ProcessBuilder> gitLaunches = []
    StdinSource stdin
    /** The stdin text of each CLI launch, in launch order. */
    final List<String> stdins = []

    /** {@code files(answerPath, stderrPath)}; the CLI is handed back after it ran. */
    ExecutorResult start(ExecutorRequest request, Process agent, Closure files = { Path answer, Path err -> }) {
        startEach(request, [agent], files)
    }

    /**
     * One start whose CLI launches are {@code agents}, in turn; {@code files} runs inside each launch,
     * as in {@link #start}, and is told the launch's 0-based number when it takes a third argument.
     */
    ExecutorResult startEach(ExecutorRequest request, List<Process> agents, Closure files = { Path answer, Path err -> }) {
        run(request, { ProcessBuilder builder, StdinSource source ->
            if (builder.command().first() == 'git') {
                gitLaunches << builder
                return view()
            }
            int launch = agentLaunches.size()
            agentLaunches << builder
            stdin = source
            stdins << stdinText()
            def answer = workspace.resolve(builder.environment()['GNOMISH_ANSWER_FILE'])
            def err = builder.redirectError().file().toPath()
            files.maximumNumberOfParameters> 2 ? files(answer, err, launch) : files(answer, err)
            agents[launch]
        } as ProcessLaunch)
    }

    /** One start over {@code launch} itself. */
    ExecutorResult run(ExecutorRequest request, ProcessLaunch launch) {
        def environment = new HostTaskExecutionEnvironment(workspace, time.clock(), ChildEnvAllowlist.none(), launch)
        environment.materialize('gnomish/TASK-7', null)
        def equipment = new ProgramRoundEquipment(bounds, time, ChildEnvAllowlist.of([], withheld, SECRETS).programGrant(),
        stderrParent, LastRegisteredCommit.NONE)
        try {
            runner(environment, equipment).start(request)
        } finally {
            environment.dispose()
        }
    }

    AgentRoundRunner<?> runner(HostTaskExecutionEnvironment environment, ProgramRoundEquipment equipment) {
        new AgentRoundRunner(NAME, otherDialect ?: dialect, environment, equipment,
                listener ?: ({ AgentProgressEvent event ->
                    progress << event
                } as AgentProgressListener), caps, answerFile)
    }

    /** The bytes the CLI was handed on its standard input, or null for none. */
    String stdinText() {
        stdin instanceof StdinSource.Content ? new String((stdin as StdinSource.Content).bytes(), 'UTF-8') : null
    }

    private Process view() {
        def next = views.isEmpty() ? CLEAN : views.remove(0)
        next instanceof Process ? next as Process : new FakeProcess(0, next as String) {}
    }

    static ExecutorRequest request(Role role, Policy policy = role == Role.TRANSFORM ? new Policy.WorkspaceWrite() : new Policy.ReadOnly()) {
        def inputs = [(Role.TRANSFORM): RoleInputs.transform('build it'),
            (Role.VERDICT): RoleInputs.verdict('is it built?'),
            (Role.DECISION): RoleInputs.decision('which one?', ['a', 'b'])][role]
        new ExecutorRequest(role, new Workspace('.'), policy, inputs, JsonNodeFactory.instance.objectNode(),
                ProgramBounds.DEFAULTS.hints(), [], null)
    }

    /** A CLI that has exited with {@code code}, its stdout waiting in the pipe. */
    static Process exited(int code, String stdout) {
        new FakeProcess(code, stdout) {}
    }
}
