package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.domain.engine.ExecutorUsage;
import com.github.oinsio.gnomish.domain.engine.ModelUsage;
import com.github.oinsio.gnomish.domain.engine.TokenUsage;
import com.github.oinsio.gnomish.domain.engine.ToolUsage;
import com.github.oinsio.gnomish.domain.engine.UsageProvenance;
import com.github.oinsio.gnomish.executorcontract.Usage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A contract result's usage as the engine records it (FR15, FR22, NFR-O1 of
 * define-executor-contract): the executor's own provenance word — a program that reports usage of
 * no model is {@code REPORTED} with an empty map, never {@code ABSENT} — each model's four token
 * counts with the cost beside them as reported (absent stays absent, never a zero), the wall time,
 * and each tool's call count and total time. An {@code ABSENT} usage carries no model, whatever the
 * file listed beside the word. Pure.
 *
 * <p>Implements FR15, FR22, NFR-O1 of define-executor-contract.
 */
final class ProgramUsage {

    private ProgramUsage() {}

    /**
     * The engine's usage carrier of {@code usage}.
     *
     * @param usage the contract usage; never null
     * @return the carrier; never null
     */
    static ExecutorUsage toEngine(Usage usage) {
        UsageProvenance provenance =
                switch (usage.provenance()) {
                    case REPORTED -> UsageProvenance.REPORTED;
                    case ABSENT -> UsageProvenance.ABSENT;
                };
        Map<String, ModelUsage> byModel = new LinkedHashMap<>();
        if (provenance == UsageProvenance.REPORTED) {
            usage.models()
                    .forEach((model, counts) -> byModel.put(
                            model,
                            new ModelUsage(
                                    new TokenUsage(
                                            counts.input(),
                                            counts.output(),
                                            counts.cacheCreation(),
                                            counts.cacheRead()),
                                    counts.costUsd())));
        }
        List<ToolUsage> tools = new ArrayList<>();
        for (var tool : usage.tools()) {
            long millis = tool.totalMillis() == null ? 0 : tool.totalMillis();
            tools.add(new ToolUsage(
                    tool.name(),
                    Math.toIntExact(Math.min(tool.calls(), Integer.MAX_VALUE)),
                    Duration.ofMillis(millis)));
        }
        Duration wall = usage.wallMillis() == null ? null : Duration.ofMillis(usage.wallMillis());
        return new ExecutorUsage(wall, tools, byModel, provenance);
    }
}
