package com.github.oinsio.gnomish.sandbox.environment

import spock.lang.Specification

/**
 * FR7 of add-sandbox-core (design D4): the guard argv builders — mitmdump in
 * non-intercepting mode on the task's internal network with the factory-rendered
 * config mounted read-only, the bridge leg as the only route out, and the
 * bounded log read behind denial findings. Pure argv assertions, no daemon.
 */
class GuardCommandsSpec extends Specification {

    static final ObjectOwnership OWNERSHIP = new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1')

    def "FR7: runGuard runs mitmdump on the task network with the config mounted read-only"() {
        when:
        def argv = GuardCommands.runGuard('k1', 'mitmproxy/mitmproxy:12', '/tmp/guard-cfg', OWNERSHIP)

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
        argv.containsAll([
            '--label',
            'com.github.oinsio.gnomish.mode=tracked'
        ])
        argv.containsAll([
            '--label',
            'com.github.oinsio.gnomish.project=proj-1'
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

    def "NFR-O1/NFR-C1: guardLogs reads a bounded, timestamped tail of the guard container log"() {
        expect: 'the first read of a guard has no cursor — the whole log from container start (D3)'
        GuardCommands.guardLogs('k1', 1000, null) == [
            'logs',
            '--tail',
            '1000',
            '--timestamps',
            'gnomish-guard-k1'
        ]
    }

    // D3 of fix-denial-report-attachment: a cursored read is the per-round delta
    def "guardLogs reads from the daemon-side cursor when one is held"() {
        expect:
        GuardCommands.guardLogs('k1', 1000, '2026-08-19T10:00:00.000000001Z') == [
            'logs',
            '--tail',
            '1000',
            '--timestamps',
            '--since',
            '2026-08-19T10:00:00.000000001Z',
            'gnomish-guard-k1'
        ]
    }

    // FR5 of fix-denial-report-attachment: the identity a durable cursor is matched against
    def "FR5: inspectGuardId asks for the guard container's runtime id"() {
        expect:
        GuardCommands.inspectGuardId('k1') == [
            'inspect',
            '-f',
            '{{.Id}}',
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
