package com.github.oinsio.gnomish.app

/**
 * Small docker helpers the container-mode E2E specs share: listing and
 * force-removing the Docker objects of one task key (box, guard, judge and
 * verification boxes, volumes, networks), so cleanup never leaks objects into
 * later specs whatever a test's outcome.
 */
class ContainerE2eDocker {

    /** All existing factory object names carrying {@code key} (box/guard/vol/net, role suffixes included). */
    static List<String> taskObjects(String key) {
        def names = []
        [
            [
                'ps',
                '-a',
                '--format',
                '{{.Names}}'
            ],
            [
                'volume',
                'ls',
                '--format',
                '{{.Name}}'
            ],
            [
                'network',
                'ls',
                '--format',
                '{{.Name}}'
            ],
        ].each { args ->
            names.addAll(run(args).readLines().findAll { it.contains(key) && it.startsWith('gnomish-') })
        }
        names
    }

    /** Force-removes every factory object of {@code key}; idempotent, best-effort. */
    static void removeTaskObjects(String key) {
        run([
            'ps',
            '-a',
            '--format',
            '{{.Names}}'
        ]).readLines()
        .findAll { it.contains(key) && it.startsWith('gnomish-') }
        .each { run(['rm', '-f', it]) }
        run([
            'volume',
            'ls',
            '--format',
            '{{.Name}}'
        ]).readLines()
        .findAll { it.contains(key) && it.startsWith('gnomish-') }
        .each { run(['volume', 'rm', '-f', it]) }
        run([
            'network',
            'ls',
            '--format',
            '{{.Name}}'
        ]).readLines()
        .findAll { it.contains(key) && it.startsWith('gnomish-') }
        .each { run(['network', 'rm', it]) }
    }

    /** Runs {@code docker exec} in a task box as the gnome user; returns merged output. */
    static String execInBox(String containerName, String script) {
        run([
            'exec',
            containerName,
            'sh',
            '-c',
            script
        ])
    }

    /** Runs {@code docker start} on a container. */
    static void start(String containerName) {
        run(['start', containerName])
    }

    /** Whether the named container currently exists (running or stopped). */
    static boolean containerExists(String containerName) {
        run([
            'ps',
            '-a',
            '--format',
            '{{.Names}}'
        ]).readLines().contains(containerName)
    }

    /** Whether the named container is currently running. */
    static boolean containerRunning(String containerName) {
        run([
            'inspect',
            '-f',
            '{{.State.Running}}',
            containerName
        ]).trim() == 'true'
    }

    private static String run(List<String> args) {
        def process = new ProcessBuilder((['docker'] + args) as String[]).redirectErrorStream(true).start()
        String output = new String(process.inputStream.readAllBytes(), 'UTF-8')
        process.waitFor()
        output
    }
}
