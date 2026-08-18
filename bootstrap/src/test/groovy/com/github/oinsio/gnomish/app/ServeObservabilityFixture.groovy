package com.github.oinsio.gnomish.app

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.app.serve.FeedAutomaton
import java.nio.file.Files
import java.nio.file.Path

/**
 * Shared scaffolding for {@link ServeObservabilityIntegrationSpec} and its
 * companion {@link ServeObservabilityRestartIntegrationSpec} (split apart
 * per process-invariants.md's file-size cap, see both classes' javadoc):
 * the minimal {@code .gnomish/} project fixture a real {@link ServeCommand}
 * run needs, the ledger/snapshot JSON readers, and the drain-only {@link
 * FeedAutomatonStarter} stub that fails loudly if drain ever reaches the
 * forever-loop starter.
 *
 * <p>Implements FR9 of add-serve-observability.
 */
trait ServeObservabilityFixture {

    private static final ObjectMapper MAPPER = new ObjectMapper()

    /** Drain never drives the forever-loop starter; fails loudly if that ever changes. */
    static class RefusingStarter implements FeedAutomatonStarter {
        @Override
        void start(FeedAutomaton automaton) {
            throw new IllegalStateException('drain must never use the forever-loop starter')
        }
    }

    /**
     * Writes the minimal {@code .gnomish/} pipeline (a single {@code build}
     * stage) and config a real {@link ServeCommand} run needs under {@code
     * projectDir}.
     */
    static void writeMinimalProject(Path projectDir) {
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/stage.yaml'), '''\
purpose: build it
executor:
  type: agent-cli
  model: model-x
instructions: stages/build/instructions.md
advancement: auto
''')
        Files.writeString(projectDir.resolve('.gnomish/config.yaml'), '''\
schemaVersion: "1"
autonomy:
  attemptLimit: 3
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
    }

    static JsonNode readJson(Path file) {
        MAPPER.readTree(file.toFile())
    }

    static List<JsonNode> readLedgerLines(Path ledgerFile) {
        ledgerFile.toFile().readLines('UTF-8').findAll {
            !it.isBlank()
        }.collect {
            MAPPER.readTree(it)
        }
    }

    static String instanceIdOf(JsonNode line) {
        line.get('instance').get('instanceId').asText()
    }
}
