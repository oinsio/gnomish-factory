package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.gittransfer.GitInvocation
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.testfixtures.standin.StandInLog
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR4, NFR-S1 of bound-subprocess-commands: which subcommands reach a remote, and what the
 * factory injects into those invocations so git's own no-progress detection — not the deadline —
 * is what governs a transfer that has stopped moving (design D5). The settings are per invocation
 * only: nothing operator-owned is written, and an operator's own {@code GIT_SSH_COMMAND} (a
 * wrapper, a jump host) is never clobbered.
 */
class GitNetworkCommandsSpec extends Specification {

    /** The invocation policy every runner command line opens with (FR1 of own-git-invocation-policy). */
    static final String POLICY = GitInvocation.configPairs().join(' ')


    @TempDir
    Path tempDir

    def "FR1: network subcommands are classified past any leading -c options; everything else is local"() {
        expect:
        GitNetworkCommands.isNetwork(args as String[]) == expected

        where:
        args | expected
        [] | false
        ['status'] | false
        ['commit', '-m', 'x'] | false
        ['fetch'] | true
        ['push'] | true
        ['ls-remote'] | true
        ['clone'] | true
        ['remote'] | false // length == 1: no second arg to inspect, must not index into args[1]
        ['remote', 'update'] | true
        ['remote', 'get-url', 'origin'] | false // a purely local read of the config
        [
            '-c',
            'protocol.ext.allow=user',
            'fetch'
        ] | true
        [
            '-c',
            'a=b',
            '-c',
            'c=d',
            'push'
        ] | true
        ['-c', 'a=b', 'status'] | false
        [
            '-c',
            'a=b',
            'remote',
            'update'
        ] | true
        ['-c', 'a=b'] | false // only -c pairs, no subcommand at all
        ['-c'] | false // dangling -c with no value
    }

    def "NFR-S2: the WARN names the subcommand, and an argv that is only options names none"() {
        expect: 'the class of command an operator reads in a timeout WARN, never the rest of the argv'
        GitNetworkCommands.subcommand(args as String[]) == expected

        where:
        args | expected
        ['push', 'origin', 'HEAD'] | 'push'
        ['-c', 'a=b', 'fetch'] | 'fetch'
        [] | 'git'
        ['-c', 'a=b'] | 'git' // only -c pairs: the argv names no subcommand at all
    }

    def "FR4: a network invocation carries git's HTTP no-progress abort ahead of the caller's own arguments"() {
        when:
        def prefixed = GitNetworkCommands.withStallDetection('push', 'origin', 'HEAD')

        then: 'global options must precede the subcommand, and the caller\'s argv survives intact'
        prefixed as List == [
            '-c',
            'http.lowSpeedLimit=1000',
            '-c',
            'http.lowSpeedTime=60',
            'push',
            'origin',
            'HEAD'
        ]
    }

    // FR1 of own-git-invocation-policy (design D2): a transfer's argv opens with the policy's pairs,
    // and the owner adds no second copy only while they stay first.
    def "FR4: an invocation the caller already configured keeps its own -c options first"() {
        when:
        def prefixed = GitNetworkCommands.withStallDetection('-c', 'protocol.ext.allow=user', 'fetch')

        then:
        prefixed as List == [
            '-c',
            'protocol.ext.allow=user',
            '-c',
            'http.lowSpeedLimit=1000',
            '-c',
            'http.lowSpeedTime=60',
            'fetch'
        ]
    }

    def "FR4: SSH transports get connect, keepalive and batch-mode limits"() {
        given:
        Map<String, String> environment = [:]

        when:
        GitNetworkCommands.applySshStallDetection(environment)

        then: 'BatchMode closes ssh\'s own prompt paths that emptying the askpass hooks cannot reach'
        environment['GIT_SSH_COMMAND'] ==
                'ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=4'
    }

    def "NFR-S1: an operator's own GIT_SSH_COMMAND is never clobbered"() {
        given: 'a wrapper the operator set — a jump host, a pinned identity file'
        Map<String, String> environment = ['GIT_SSH_COMMAND': 'ssh -J bastion.example.com']

        when:
        GitNetworkCommands.applySshStallDetection(environment)

        then:
        environment['GIT_SSH_COMMAND'] == 'ssh -J bastion.example.com'
    }

    // Scenario: git/git-task-persistence / Operator git configuration is not modified
    def "FR4: the settings reach the child process itself, and only the network ones"() {
        given: 'a git stand-in that records both the argv and the ssh command it was handed'
        def fakeGit = StandIn.recording(tempDir, 'record-ssh')
        def runner = new GitProcessRunner(fakeGit.toString())

        when:
        runner.run(tempDir, 'status')
        runner.run(tempDir, 'ls-remote', 'origin')
        def (local, network) = StandInLog.blocks(fakeGit)

        then: 'a local command is handed only the invocation policy — no network options, no ssh wrapper'
        local.argv == "${POLICY} status"
        !local.GIT_SSH_COMMAND.contains('BatchMode')

        and: 'a network command carries the HTTP no-progress abort ahead of its own arguments'
        network.argv == "${POLICY} -c http.lowSpeedLimit=1000 -c http.lowSpeedTime=60 ls-remote origin"

        and: 'and the SSH connect/keepalive limits, in the child environment only'
        network.GIT_SSH_COMMAND == '[ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=15' +
                ' -o ServerAliveCountMax=4]'
    }
}
