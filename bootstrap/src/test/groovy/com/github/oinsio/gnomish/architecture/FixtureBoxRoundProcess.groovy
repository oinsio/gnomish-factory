package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.adapter.git.LocalBoxEnvironment
import com.github.oinsio.gnomish.sandbox.ExecCommand
import java.nio.file.Files
import java.nio.file.Path

/**
 * The child-JVM half of {@link TestEnvironmentHygieneSpec} (design D14 of
 * make-checkpoint-gate-durable): one fake-agent round through the fixture box, run in a JVM
 * whose own environment the spec composed — the only way to plant a variable in the parent
 * environment of the box's {@link ProcessBuilder}, since a running JVM cannot change its own.
 *
 * <p>Arguments: the factory clone, the box root, the branch to materialize, then the fake agent's
 * command. The round plays {@code decision-needed} and names no decision file of its own (a judge
 * vote's or a check's shape), so the only {@code GNOMISH_DECISION_FILE} the fake could write to is
 * one the box let through from this JVM. Exits with the fake's exit code.
 *
 * <p>FR22, M10 of make-checkpoint-gate-durable.
 */
final class FixtureBoxRoundProcess {

    private FixtureBoxRoundProcess() {}

    /** The line this process prints first, so the spec can prove the plant reached this JVM. */
    static final String PLANTED_PREFIX = 'parent GNOMISH_DECISION_FILE='

    static void main(String[] args) {
        println(PLANTED_PREFIX + System.getenv('GNOMISH_DECISION_FILE'))
        def box = new LocalBoxEnvironment(Path.of(args[0]), Files.createDirectories(Path.of(args[1])))
        box.materialize(args[2], null)
        def command = new ExecCommand(args.drop(3).toList(), [GNOMISH_FAKE_SCENARIO: 'decision-needed'], null, true)
        def handle = box.exec(command)
        handle.output().transferTo(System.out)
        System.exit(handle.waitForExit())
    }
}
