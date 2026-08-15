package com.github.oinsio.gnomish.sandbox.environment

import spock.lang.Specification

/**
 * FR7 of add-sandbox-core (design D4): the guard argv builders — mitmdump in
 * non-intercepting mode on the task's internal network with the factory-rendered
 * config mounted read-only, the bridge leg as the only route out, and the
 * bounded log read behind denial findings. Pure argv assertions, no daemon.
 */
class GuardCommandsSpec extends Specification {

    def "FR7: runGuard runs mitmdump on the task network with the config mounted read-only"() {
        when:
        def argv = GuardCommands.runGuard('k1', 'mitmproxy/mitmproxy:12', '/tmp/guard-cfg')

        then: 'the guard container is named, labelled, and joined to the task network'
        argv.containsAll(['--name', 'gnomish-guard-k1'])
        argv.containsAll(['--network', 'gnomish-net-k1'])

        and: 'it carries the stable alias baked image configs dial (task 9.1, D7)'
        argv.containsAll([
            '--network-alias',
            'gnomish-guard'
        ])
        argv.containsAll([
            '--label',
            'com.github.oinsio.gnomish.factory=true'
        ])
        argv.containsAll([
            '--label',
            'com.github.oinsio.gnomish.task=k1'
        ])

        and: 'the factory-rendered config is mounted read-only (NFR-S2)'
        argv.containsAll([
            '-v',
            '/tmp/guard-cfg:/gnomish-guard:ro'
        ])

        and: 'mitmdump runs the addon script in regular mode on the guard port, lazy upstream'
        argv.indexOf('mitmproxy/mitmproxy:12') <argv.indexOf('mitmdump')
        argv.containsAll(['--mode', 'regular'])
        argv.containsAll(['--listen-port', '8080'])
        argv.containsAll([
            '--set',
            'connection_strategy=lazy'
        ])
        argv.containsAll([
            '-s',
            '/gnomish-guard/guard.py'
        ])
    }

    def "FR7: connectBridge gives the guard its only route out"() {
        expect:
        GuardCommands.connectBridge('k1') == [
            'network',
            'connect',
            'bridge',
            'gnomish-guard-k1'
        ]
    }

    def "NFR-O1/NFR-C1: guardLogs reads a bounded tail of the guard container log"() {
        expect:
        GuardCommands.guardLogs('k1', 1000) == [
            'logs',
            '--tail',
            '1000',
            'gnomish-guard-k1'
        ]
    }

    def "NFR-R1: the guard outage probe and repair commands address the guard by name"() {
        expect:
        GuardCommands.inspectGuardRunning('k1') == [
            'inspect',
            '-f',
            '{{.State.Running}}',
            'gnomish-guard-k1'
        ]
        GuardCommands.startGuard('k1') == ['start', 'gnomish-guard-k1']
        GuardCommands.removeGuard('k1') == [
            'rm',
            '-f',
            'gnomish-guard-k1'
        ]
    }

    def "FR8: the isolation probes inspect the task network internal flag and the container runtime"() {
        expect:
        GuardCommands.inspectNetworkInternal('k1') == [
            'network',
            'inspect',
            '-f',
            '{{.Internal}}',
            'gnomish-net-k1'
        ]
        GuardCommands.inspectRuntime('k1') == [
            'inspect',
            '-f',
            '{{.HostConfig.Runtime}}',
            'gnomish-box-k1'
        ]
    }
}
