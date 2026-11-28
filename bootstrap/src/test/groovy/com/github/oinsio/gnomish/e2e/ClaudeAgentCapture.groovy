package com.github.oinsio.gnomish.e2e

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path

/**
 * One fake agent binary of {@link ClaudeRoundJarE2eSpec} and what it recorded (task 7.2 of
 * extract-agent-round-runner): a per-run link to the committed preset {@code
 * [agent-judged-capturing-round]}, named after the scenario it plays, whose {@code .log} holds every
 * prompt it was started with and whose {@code .argv} every argument list, in launch order.
 */
final class ClaudeAgentCapture {

    final Path binary

    private ClaudeAgentCapture(Path binary) {
        this.binary = binary
    }

    /** A capturing two-role agent playing {@code scenario}, in a folder of its own. */
    static ClaudeAgentCapture playing(String scenario) {
        Path link = Files.createTempDirectory('gnomish-e2e-agent').resolve(scenario)
        new ClaudeAgentCapture(StandIn.link(link, 'agent-judged-capturing-round'))
    }

    /** The prompts (stdin) of every invocation, in launch order. */
    List<String> prompts() {
        Path log = StandIn.log(binary)
        Files.exists(log) ? Files.readString(log).split('\n---\n').findAll {
            !it.isBlank()
        } as List<String> : []
    }

    /** The argv of every invocation, in launch order. */
    List<List<String>> argvs() {
        Path file = StandIn.beside(binary, 'argv')
        Files.exists(file) ? FakeAgentSupport.capturedInvocations(file) : []
    }

    /** Per invocation, in launch order: whether it was a judge verdict request ({@code --model judge-model}). */
    List<Boolean> judged() {
        argvs().collect { argv ->
            int at = argv.indexOf('--model')
            at >= 0 && argv[at + 1] == FakeAgentSupport.JUDGE_MODEL
        }
    }

    @Override
    String toString() {
        binary.toString()
    }
}
