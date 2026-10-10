package com.github.oinsio.gnomish.e2e.paidsmoke

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import groovy.transform.CompileStatic
import java.nio.file.Files
import java.nio.file.Path

/**
 * A recording stand-in for the agent CLI binary, handed to a real {@code gnomish run} as
 * {@code factory.agent-cli-binary}: it writes the argv the factory launched it with and a copy
 * of the round's stream-json stdout, then hands every argument, stdin and the exit code to the
 * real {@code claude} unchanged. It adds no flag of its own — it observes the command line the
 * factory builds, it does not repair it, so it is not the agent-CLI wrapper the operator guides
 * no longer recommend.
 *
 * <p>The recorder is the committed {@code argv-recorder} stand-in (ADR 0015), reached through a
 * per-run link whose neighbours name the real binary and the capture directory: a host-mode
 * round's environment is the child allowlist, so a variable naming them would never reach it.
 *
 * <p>Implements task 6.2 of fix-operator-blockers (FR1, FR2, FR3, M1 on the real CLI).
 *
 * <p>Arguments are recorded NUL-separated, so an argument spanning lines stays one argument;
 * each round's capture name comes from {@code mktemp}, since a process id can be reused
 * within one run.
 */
@CompileStatic
final class ArgvRecorder {

    final Path script

    final Path captureDir

    private ArgvRecorder(Path script, Path captureDir) {
        this.script = script
        this.captureDir = captureDir
    }

    /**
     * @param realBinary absolute path of the real CLI the recorder hands off to
     * @param root a scratch directory for the script and its captures
     * @return the recorder, its script a link to the committed preset
     */
    static ArgvRecorder create(Path realBinary, Path root) {
        Path script = StandIn.link(root.resolve('claude-recorder'), 'argv-recorder')
        StandIn.beside(script, 'real-binary').toFile().text = realBinary.toString()
        Path captures = Files.createDirectories(StandIn.beside(script, 'captures'))
        new ArgvRecorder(script, captures)
    }

    /** @return every recorded round, in no particular order */
    List<Round> rounds() {
        List<Path> argvFiles = Files.list(captureDir).withCloseable { stream ->
            stream.filter { Path it ->
                it.fileName.toString().endsWith('.argv')
            }.toList()
        }
        argvFiles.collect { Path argvFile ->
            Path transcript = captureDir.resolve(argvFile.fileName.toString().replace('.argv', '.jsonl'))
            List<String> argv = Files.readString(argvFile).split('\u0000').toList()
            new Round(argv, Files.exists(transcript) ? events(transcript) : [])
        }
    }

    private static List<Map<String, Object>> events(Path transcript) {
        def mapper = new ObjectMapper()
        Files.readAllLines(transcript).findAll { String it ->
            it.startsWith('{')
        }.collect { String it ->
            (Map<String, Object>) mapper.readValue(it, Map)
        }
    }

    /** One recorded round: the argv after the binary, and the parsed stream-json events. */
    static final class Round {

        final List<String> argv

        final List<Map<String, Object>> events

        Round(List<String> argv, List<Map<String, Object>> events) {
            this.argv = argv
            this.events = events
        }

        /** @return the value following {@code --permission-mode}, or null */
        String argvMode() {
            int at = argv.indexOf('--permission-mode')
            at >= 0 && at + 1 <argv.size() ? argv[at + 1] : null
        }

        /** @return the CLI's own {@code system/init} event, or null when the round never started */
        Map<String, Object> init() {
            events.find { Map<String, Object> it ->
                it.type == 'system' && it.subtype == 'init'
            }
        }

        /** @return the final {@code result} event, or null when the round never finished */
        Map<String, Object> result() {
            events.find { Map<String, Object> it -> it.type == 'result' }
        }

        /** @return the name of every tool the model asked to call, in order */
        List<String> toolCalls() {
            List<String> names = []
            events.findAll { Map<String, Object> it ->
                it.type == 'assistant'
            }.each { Map<String, Object> event ->
                Map<String, Object> message = (Map<String, Object>) (event.message ?: [:])
                List<Map<String, Object>> content = (List<Map<String, Object>>) (message.content ?: [])
                content.findAll { Map<String, Object> it ->
                    it.type == 'tool_use'
                }.each {
                    names << (String) it.name
                }
            }
            names
        }

        @Override
        String toString() {
            "mode=${argvMode()} argv=${argv}"
        }
    }
}
