package com.github.oinsio.gnomish.e2e

import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.charset.StandardCharsets
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Records every container and network event the Docker daemon reports while a spec acts, so the
 * spec can show what the act created — including a {@code --rm} container that is gone by the time
 * any listing could look for it (the box git floor helper is one: {@code docker run --rm}).
 *
 * <p>The window is bounded by order, never by clock: {@link #open} creates a sentinel network and
 * waits until the stream reports it, so the subscription is live before the act; {@link #close}
 * creates a second sentinel and waits for it, and since the daemon reports events in order, every
 * event of the act has arrived by then. No wall-clock {@code --since}/{@code --until} is involved,
 * so a daemon whose clock differs from the host's (a desktop VM) cannot shift the window.
 *
 * <p>Each recorded line is {@code <type> <action> <image> <labels>}: container events carry the
 * container's image and every label as attributes, so a factory object is recognised by its
 * project label or its image, whatever its name.
 *
 * <p>NFR-R1, NG1 of add-doctor-command.
 */
final class DockerEventWatch {

    private static final String FORMAT =
    '{{.Type}} {{.Action}} {{index .Actor.Attributes "image"}} {{json .Actor.Attributes}}'
    private static final long SENTINEL_WAIT_SECONDS = 60

    private final Process events
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>()
    private final Thread reader
    private final String token

    private DockerEventWatch(Process events, String token) {
        this.events = events
        this.token = token
        this.reader = Thread.ofVirtual().start {
            events.inputStream.newReader(StandardCharsets.UTF_8.name()).eachLine {
                lines.add(it)
            }
        }
    }

    /**
     * Subscribes to the daemon's container and network events and returns once the stream has
     * reported this watch's opening sentinel.
     *
     * @param token a unique word naming this watch's sentinel networks
     */
    static DockerEventWatch open(String token) {
        ProcessBuilder builder = new ProcessBuilder(
                'docker', 'events', '--filter', 'type=container', '--filter', 'type=network', '--format', FORMAT)
        TestChildEnvironment.cleared(builder)
        builder.redirectError(ProcessBuilder.Redirect.DISCARD)
        DockerEventWatch watch = new DockerEventWatch(builder.start(), token)
        watch.sentinel('open')
        watch
    }

    /**
     * Ends the window: waits for the closing sentinel, stops the stream and removes both
     * sentinels.
     *
     * @return every event recorded between the two sentinels, the sentinels' own excluded
     */
    List<String> close() {
        try {
            List<String> recorded = sentinel('close')
            return recorded.findAll { !it.contains(sentinelName('open')) }
        } finally {
            events.destroy()
            reader.join(TimeUnit.SECONDS.toMillis(SENTINEL_WAIT_SECONDS))
            ['open', 'close'].each { docker('network', 'rm', sentinelName(it)) }
        }
    }

    /** Creates the sentinel network {@code which} and returns the events recorded before it. */
    private List<String> sentinel(String which) {
        String name = sentinelName(which)
        assert docker('network', 'create', name) == 0: "docker network create ${name} failed"
        List<String> before = []
        while (true) {
            String line = lines.poll(SENTINEL_WAIT_SECONDS, TimeUnit.SECONDS)
            assert line != null: "docker events never reported the sentinel ${name}; recorded: ${before}"
            if (line.startsWith('network create') && line.contains("\"name\":\"${name}\"")) {
                return before
            }
            before.add(line)
        }
    }

    /** Whether the daemon holds {@code image} locally — the run's "no pull" is its absence after. */
    static boolean imagePresent(String image) {
        docker('image', 'inspect', image) == 0
    }

    private String sentinelName(String which) {
        "gnomish-doctor-e2e-${which}-${token}"
    }

    private static int docker(String... args) {
        ProcessBuilder builder = new ProcessBuilder(['docker'] + args.toList())
        TestChildEnvironment.cleared(builder)
        builder.redirectErrorStream(true)
        Process process = builder.start()
        process.inputStream.text
        assert process.waitFor(SENTINEL_WAIT_SECONDS, TimeUnit.SECONDS): "docker ${args.join(' ')} did not exit"
        process.exitValue()
    }
}
