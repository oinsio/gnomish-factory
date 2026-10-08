package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The nightly whole-tree mutation run reports its failure through
 * {@code scripts/nightly-mutation-issue.sh} (FR6, UX4 of scope-pit-locally, design D7). On CI the
 * script runs only on a red night, so a regression in it — a lost module list, a second issue
 * opened beside the open one — would surface only when the report is needed. This spec drives
 * both paths over a canned Gradle log against a stubbed {@code gh} first on {@code PATH}, which
 * records every call and the body it was handed, and pins that the workflow really calls it.
 */
class NightlyMutationIssueScriptSpec extends Specification {

    private static final String RUN_URL = 'https://github.com/o/r/actions/runs/42'

    /** Two failed tasks in the shapes Gradle prints, plus a task only the progress line names. */
    private static final String LOG = '''\
> Task :domain:pitest
> Task :adapters:git:pitest FAILED
> Task :domain:pitestVerifyAllKilled FAILED

FAILURE: Build completed with 2 failures.

1: Task failed with an exception.
-----------
* What went wrong:
Execution failed for task ':domain:pitestVerifyAllKilled'.
> PIT mutation gate is not trustworthy: 2 mutation(s) were not KILLED [SURVIVED:2]. PIT counts...
  First offenders:

2: Task failed with an exception.
-----------
* What went wrong:
Execution failed for task ':bootstrap:test'.
> There were failing tests. See the report at: file:///x/index.html
'''

    @TempDir
    Path work

    def "UX4: with no open issue, one is created naming every failed task with its reason"() {
        when:
        def run = runScript(label: '', openIssue: '')

        then:
        run.exit == 0
        calls() == [
            'label list --search nightly-mutation --json name --jq .[] | select(.name == "nightly-mutation") | .name',
            'label create nightly-mutation --description The nightly whole-tree mutation run failed --color B60205',
            'issue list --label nightly-mutation --state open --json number --jq sort_by(.number) | .[0].number // empty',
            'issue create --title Nightly mutation run failed --label nightly-mutation --body-file -'
        ]

        and: 'the body is actionable on its own: tasks, reasons, run, artifact'
        def body = body()
        body.contains('- `:domain:pitestVerifyAllKilled`: PIT mutation gate is not trustworthy: 2 mutation(s) were not KILLED [SURVIVED:2]. PIT counts...')
        body.contains('- `:bootstrap:test`: There were failing tests. See the report at: file:///x/index.html')
        body.contains('- `:adapters:git:pitest`\n')
        !body.contains(':domain:pitest`')
        body.contains("Run: ${RUN_URL}")
        body.contains('the `pit-report` artifact')
    }

    def "FR6: with an issue still open, the report is a comment on it and no issue is created"() {
        when:
        def run = runScript(label: 'nightly-mutation', openIssue: '17')

        then:
        run.exit == 0
        calls().takeRight(1) == [
            'issue comment 17 --body-file -'
        ]
        !calls().any {
            it.startsWith('label create') || it.startsWith('issue create')
        }
        body().contains('- `:domain:pitestVerifyAllKilled`: PIT mutation gate')
    }

    def "UX4: a log with no failed task still yields an issue that says so"() {
        given:
        Files.writeString(work.resolve('nightly.log'), 'Could not resolve the wrapper\n')

        when:
        def run = runScript(label: 'nightly-mutation', openIssue: '', log: false)

        then:
        run.exit == 0
        body().contains('no failed Gradle task was found in the log')
    }

    def "UX4: a run that failed before Gradle wrote any log still yields an issue that says so"() {
        when: 'a setup step failed, so the step that tees the log never ran'
        def run = runScript(label: 'nightly-mutation', openIssue: '', log: false)

        then:
        run.exit == 0
        body().contains('no failed Gradle task was found in the log')
        body().contains("Run: ${RUN_URL}")
    }

    def "FR6: the script refuses a call without its three arguments"() {
        expect:
        execute([
            'bash',
            script(),
            'nightly.log'
        ], [:]).exit == 2
    }

    // FR6: the workflow runs the failure report through this script, so the features above test what CI runs
    def "FR6: the nightly workflow reports a failure through the script"() {
        given:
        def workflow = Files.readString(RepoSourceTree.repoRoot().resolve('.github/workflows/pitest-nightly.yml'))

        expect:
        workflow.contains('if: failure()')
        workflow.contains('run: bash scripts/nightly-mutation-issue.sh build/nightly.log')
    }

    /** Runs the script with a stub {@code gh} answering the label and open-issue lookups. */
    private Map runScript(Map stub) {
        if (stub.log != false) {
            Files.writeString(work.resolve('nightly.log'), LOG)
        }
        def bin = Files.createDirectories(work.resolve('bin'))
        def gh = bin.resolve('gh')
        Files.writeString(gh, '''\
#!/bin/bash
printf '%s\\n' "$*" >> "$STUB_DIR/calls"
case "$1 $2" in
    'label list') [ -n "$STUB_LABEL" ] && echo "$STUB_LABEL" ;;
    'issue list') [ -n "$STUB_OPEN_ISSUE" ] && echo "$STUB_OPEN_ISSUE" ;;
    'issue comment'|'issue create') cat > "$STUB_DIR/body" ;;
esac
exit 0
''')
        gh.toFile().setExecutable(true)
        execute([
            'bash',
            script(),
            'nightly.log',
            RUN_URL,
            'pit-report'
        ], [
            PATH: "${bin}:${System.getenv('PATH')}".toString(),
            STUB_DIR: work.toString(),
            STUB_LABEL: stub.label as String,
            STUB_OPEN_ISSUE: stub.openIssue as String
        ])
    }

    private Map execute(List<String> command, Map<String, String> env) {
        def builder = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true)
        // Nothing of the test run's environment beyond the test child baseline (design D14 of
        // make-checkpoint-gate-durable); the stubs' own variables and PATH go on top.
        TestChildEnvironment.cleared(builder).putAll(env)
        def process = builder.start()
        def output = process.inputStream.text
        [exit: process.waitFor(), output: output]
    }

    private List<String> calls() {
        Files.readAllLines(work.resolve('calls'))
    }

    private String body() {
        Files.readString(work.resolve('body'))
    }

    private static String script() {
        RepoSourceTree.repoRoot().resolve('scripts/nightly-mutation-issue.sh').toString()
    }
}
