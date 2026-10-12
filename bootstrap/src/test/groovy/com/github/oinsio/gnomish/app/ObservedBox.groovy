package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.DenialCursor
import com.github.oinsio.gnomish.sandbox.DenialRead
import com.github.oinsio.gnomish.sandbox.DenialRestoration
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import java.util.concurrent.ConcurrentHashMap
import org.jspecify.annotations.Nullable

/**
 * A pass-through view of a box for the sandbox round flow specs: it forwards every port call to
 * the box it wraps and observes what the factory did through the port — each file-channel call
 * with the number of processes still running in the box at that moment (a process counts as
 * running from {@code exec} until one of its waits returns) — and lets a spec stand in for what
 * the gnome or the runtime does at the agent's launch: write into the working copy, answer the
 * exec in place of the box, or make the denial source unreadable.
 */
class ObservedBox implements TaskExecutionEnvironment {

    /** One file-channel call: the operation, the path, and the processes running at that moment. */
    static record ChannelCall(String operation, String path, int running) {}

    final TaskExecutionEnvironment box
    final List<ChannelCall> channelCalls = [].asSynchronized()
    private final Set<Object> running = ConcurrentHashMap.newKeySet()

    /** Runs at each agent launch (an exec naming a decision file), before the process starts. */
    Closure atAgentLaunch = null

    /** When set, answers an agent launch in place of the box (a runtime that stopped answering). */
    Closure<ExecHandle> agentLaunchAnswer = null

    /** When set, the denial source cannot be read at all: {@link #readDenials} throws. */
    boolean denialSourceUnreadable = false

    ObservedBox(TaskExecutionEnvironment box) {
        this.box = box
    }

    @Override
    ExecHandle exec(ExecCommand command) {
        boolean agent = command.env().containsKey('GNOMISH_DECISION_FILE')
        if (agent && atAgentLaunch != null) {
            atAgentLaunch(command)
        }
        ExecHandle handle = agent && agentLaunchAnswer != null ? agentLaunchAnswer(command) : box.exec(command)
        Object token = new Object()
        running << token
        new ExecHandle() {
                    @Override
                    InputStream output() {
                        handle.output()
                    }

                    @Override
                    Instant startedAt() {
                        handle.startedAt()
                    }

                    @Override
                    ExecHandle.Wait waitForExitOrTimeout(Duration timeout, InstantSource clock) {
                        try {
                            handle.waitForExitOrTimeout(timeout, clock)
                        } finally {
                            running.remove(token)
                        }
                    }

                    @Override
                    int waitForExit() {
                        try {
                            handle.waitForExit()
                        } finally {
                            running.remove(token)
                        }
                    }
                }
    }

    @Override
    void putFile(String path, byte[] content) {
        channelCalls << new ChannelCall('putFile', path, running.size())
        box.putFile(path, content)
    }

    @Override
    Optional<byte[]> readFile(String path, long sizeCap) {
        channelCalls << new ChannelCall('readFile', path, running.size())
        box.readFile(path, sizeCap)
    }

    @Override
    DenialRead readDenials() {
        if (denialSourceUnreadable) {
            throw new UncheckedIOException(new IOException('denial log unreadable'))
        }
        box.readDenials()
    }

    @Override
    void materialize(String branch, @Nullable String commitPin) {
        box.materialize(branch, commitPin)
    }

    @Override
    void harvest() {
        box.harvest()
    }

    @Override
    void dispose() {
        box.dispose()
    }

    @Override
    String scratchRoot() {
        box.scratchRoot()
    }

    @Override
    CapabilityPassport passport() {
        box.passport()
    }

    @Override
    Optional<DenialCursor> denialCursor() {
        box.denialCursor()
    }

    @Override
    void restoreDenials(DenialRestoration restoration) {
        box.restoreDenials(restoration)
    }
}
