package com.github.oinsio.gnomish.adapter.git.state

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Verifies {@link TraceLineWriter} against the {@code trace.jsonl} contract
 * (FR3, design D3/D5): the relative-path shape, the per-line JSON round-trip,
 * and the full file-writer behavior — one line per call in order, empty-calls
 * handling, and parent-directory creation.
 *
 * FR3: trace.jsonl line writer for attempts/<stage>/<round>/.
 */
class TraceLineWriterSpec extends Specification {

    @TempDir
    Path root

    def start = Instant.parse("2026-07-18T09:00:00Z")

    def "relativePath resolves attempts/<stage>/<round>/trace.jsonl"() {
        given:
        def key = new AttemptKey("task-1", "build", 2)

        expect:
        TraceLineWriter.relativePath(key) == Path.of("attempts", "build", "2", "trace.jsonl")
    }

    def "renderLine produces a compact JSON line that round-trips seq, tool, start, durationMillis"() {
        given:
        def call = new ToolCall(0, "run_tests", start, Duration.ofMillis(1234))

        when:
        def line = TraceLineWriter.renderLine(call)
        def dto = TaskStateJson.mapper().readValue(line, TraceLineDto)

        then:
        !line.contains("\n")
        dto.seq() == 0
        dto.tool() == "run_tests"
        dto.start() == "2026-07-18T09:00:00Z"
        dto.durationMillis() == 1234
    }

    def "write creates the file at the expected relative path with one line per call in order"() {
        given:
        def key = new AttemptKey("task-1", "build", 2)
        def calls = [
            new ToolCall(0, "read_file", start, Duration.ofMillis(10)),
            new ToolCall(1, "run_tests", start.plusSeconds(1), Duration.ofMillis(2000)),
            new ToolCall(2, "write_file", start.plusSeconds(2), Duration.ZERO),
        ]
        def trace = new ToolTrace(key, calls)

        when:
        TraceLineWriter.write(root, trace)
        def target = root.resolve("attempts").resolve("build").resolve("2").resolve("trace.jsonl")
        def lines = Files.readAllLines(target)

        then:
        Files.exists(target)
        lines.size() == 3

        when:
        def dtos = lines.collect { TaskStateJson.mapper().readValue(it, TraceLineDto) }

        then:
        dtos*.seq() == [0, 1, 2]
        dtos*.tool() == [
            "read_file",
            "run_tests",
            "write_file"
        ]
        dtos[1].durationMillis() == 2000
        dtos[2].durationMillis() == 0
    }

    def "write with empty calls still produces a present, zero-line file"() {
        given:
        def key = new AttemptKey("task-1", "review", 0)
        def trace = new ToolTrace(key, [])

        when:
        TraceLineWriter.write(root, trace)
        def target = root.resolve("attempts").resolve("review").resolve("0").resolve("trace.jsonl")

        then:
        Files.exists(target)
        Files.readString(target) == ""
    }

    def "write creates parent directories that do not yet exist"() {
        given:
        def key = new AttemptKey("task-1", "design", 5)
        def trace = new ToolTrace(key, [
            new ToolCall(0, "search", start, Duration.ofMillis(5))
        ])

        expect:
        !Files.exists(root.resolve("attempts"))

        when:
        TraceLineWriter.write(root, trace)

        then:
        Files.isDirectory(root.resolve("attempts").resolve("design").resolve("5"))
        Files.exists(root.resolve("attempts").resolve("design").resolve("5").resolve("trace.jsonl"))
    }

    def "write overwrites an existing file at the same round path"() {
        given:
        def key = new AttemptKey("task-1", "build", 1)
        def target = root.resolve("attempts").resolve("build").resolve("1").resolve("trace.jsonl")
        Files.createDirectories(target.getParent())
        Files.writeString(target, "stale content\n")

        when:
        TraceLineWriter.write(root, new ToolTrace(key, [
            new ToolCall(0, "fresh", start, Duration.ofMillis(1))
        ]))
        def lines = Files.readAllLines(target)

        then:
        lines.size() == 1
        !lines[0].contains("stale content")
    }
}
