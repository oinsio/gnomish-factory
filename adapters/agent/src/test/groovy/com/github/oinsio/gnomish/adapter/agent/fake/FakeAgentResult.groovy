package com.github.oinsio.gnomish.adapter.agent.fake

/**
 * The outcome of one fake-agent subprocess run: the captured exit code and
 * the raw stdout, split into lines the way a stream-json parser would consume
 * them, plus the raw stderr for diagnosing harness-level failures (unknown
 * scenario, missing env var — see {@code fake-agent.sh}).
 *
 * <p>Task 2.1, FR15 of add-agent-executor. Not production code.
 *
 * @param exitCode the process's exit code
 * @param stdoutLines stdout split on newlines, blank trailing lines dropped
 * @param stderr the raw merged stderr text
 */
record FakeAgentResult(int exitCode, List<String> stdoutLines, String stderr) {}
