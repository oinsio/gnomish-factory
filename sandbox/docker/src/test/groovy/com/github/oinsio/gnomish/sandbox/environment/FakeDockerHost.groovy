package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal
import java.time.Instant

/**
 * A stateful fake Docker host for the sweep-lifecycle convergence spec: unlike {@link
 * RecordingDockerCli}'s scripted closures — which answer every call from a fixed script, so a
 * second pass sees the same host it saw the first time — this fake actually APPLIES the sweep's
 * destructive commands to its own object set. That is what makes a second {@code evaluate} over
 * the same host a meaningful question at all (NFR-R2: re-running after a crash mid-action
 * converges to the same end state).
 *
 * <p>Objects are held in insertion order and keyed by their docker name; the label map, the
 * timing, and the created-at instant are exactly what the real listing/inspect commands would
 * report for them.
 */
class FakeDockerHost {

    /** One object on the fake host — the three fields every listing or inspect command reads. */
    static class Obj {
        String name
        ObjectKind kind
        Map<String, String> labels
        Instant createdAt
        Instant finishedAt
        boolean running
    }

    final Map<String, Obj> objects = [:]

    /** Every destructive command this host actually applied, in order — the convergence evidence. */
    final List<List<String>> mutations = []

    void add(String name, ObjectKind kind, Map<String, String> labels, Instant createdAt,
            boolean running = false, Instant finishedAt = null) {
        objects[name] = new Obj(name: name, kind: kind, labels: labels, createdAt: createdAt,
        running: running, finishedAt: finishedAt)
    }

    /** The {@code TaskEnvironmentDisposal} the sweep disposes key triples through — really removes them. */
    TaskEnvironmentDisposal disposal() {
        { String key ->
            mutations << ['dispose-triple', key]
            objects.values().removeAll {
                it.labels[FactoryDockerLabels.TASK_LABEL] == key
            }
        } as TaskEnvironmentDisposal
    }

    /** The {@code onRun} closure a {@link RecordingDockerCli} answers every command through. */
    Closure<DockerResult> commands() {
        { List<String> args -> answer(args) }
    }

    private DockerResult answer(List<String> args) {
        def listed = listing(args)
        if (listed != null) {
            return ok(listed)
        }
        def probed = existenceProbe(args)
        if (probed != null) {
            return objects.containsKey(probed) ? ok(probed) : new DockerResult(1, '', 'Error: No such object')
        }
        def inspected = inspectTarget(args)
        if (inspected != null) {
            return inspectAnswer(args, inspected)
        }
        return mutate(args)
    }

    private String listing(List<String> args) {
        def kind = [
            (DockerLifecycleCommands.listFactoryContainersWithLabels(PROJECT)): ObjectKind.CONTAINER,
            (DockerLifecycleCommands.listFactoryVolumesWithLabels(PROJECT)): ObjectKind.VOLUME,
            (DockerLifecycleCommands.listFactoryNetworksWithLabels(PROJECT)): ObjectKind.NETWORK
        ][args]
        if (kind == null) {
            return null
        }
        objects.values().findAll { it.kind == kind }
        .collect { "${it.name}\t${renderLabels(it.labels)}" }
        .join('\n') + '\n'
    }

    private static String renderLabels(Map<String, String> labels) {
        labels.collect { k, v -> "${k}=${v}" }.join(',')
    }

    /** The name a "does this still exist" probe asks about, or null when this is not a probe. */
    private static String existenceProbe(List<String> args) {
        args.any { it == '{{.Id}}' || it == '{{.Name}}' } ? args.last() : null
    }

    private Obj inspectTarget(List<String> args) {
        if (args.first() != 'inspect' && !(args.size() > 1 && args[1] == 'inspect')) {
            return null
        }
        objects[args.last()]
    }

    private static DockerResult inspectAnswer(List<String> args, Obj obj) {
        if (obj.kind == ObjectKind.CONTAINER) {
            def finished = obj.finishedAt ?: Instant.parse('0001-01-01T00:00:00Z')
            return ok("${obj.running} ${finished} ${obj.createdAt} ${obj.createdAt}")
        }
        obj.kind == ObjectKind.NETWORK ? ok("\"${obj.createdAt}\"") : ok(obj.createdAt.toString())
    }

    /** Applies a destructive command to the host, exactly as the daemon would. */
    private DockerResult mutate(List<String> args) {
        def target = args.last()
        if (args == DockerCommands.stop(target)) {
            def obj = objects[target]
            if (obj == null) {
                return new DockerResult(1, '', 'Error: No such container')
            }
            mutations << args
            obj.running = false
            obj.finishedAt = STOPPED_AT
            return ok('')
        }
        if (args == DockerCommands.removeContainer(target)
                || args == DockerCommands.removeVolume(target)
                || args == DockerCommands.removeNetwork(target)) {
            if (!objects.containsKey(target)) {
                return new DockerResult(1, '', 'Error: No such object')
            }
            mutations << args
            objects.remove(target)
            return ok('')
        }
        ok('')
    }

    private static DockerResult ok(String stdout) {
        new DockerResult(0, stdout, '')
    }

    static final String PROJECT = 'proj-1'
    static final Instant STOPPED_AT = Instant.parse('2026-08-07T11:59:00Z')
}
