package com.github.oinsio.gnomish.adapter.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Renders a stage/check's first-class {@code model} and its opaque {@code
 * settings} map into the CLI invocation flags {@link AgentProcessLauncher}
 * appends after the transport tokens (design D7): {@code --model} always,
 * then {@code --allowedTools}/{@code --disallowedTools}/{@code --max-turns}
 * when the corresponding settings key is present.
 *
 * <p>Both {@link com.github.oinsio.gnomish.domain.pipeline.StageDefinition.Executor}
 * and {@link com.github.oinsio.gnomish.domain.pipeline.VerifyCheck.Judge} carry
 * the same {@code (model, settings)} shape with no shared interface, so this
 * renderer takes the two plain values directly rather than depending on
 * either record — one method serves both call sites (an executor round and a
 * judge round).
 *
 * <p>{@code settings} is the opaque, already-validated map load-pipeline-config
 * hands the domain (plain JDK types only — String/Number/Boolean/List/Map);
 * this class does not validate it. Schema validation (unknown keys, malformed
 * values) is a startup-time concern owned by {@link AgentSettingsValidator}
 * (task 9.1) — by the time a round is launched, the map is well-formed. An
 * unrecognized key is silently ignored rather than rejected, as cheap defense
 * in depth, not a substitute for that validator. {@code roundTimeout} is
 * deliberately never rendered here: it is not a CLI flag at all, but
 * engine/adapter-side process timeout enforcement (task 4.5).
 *
 * <p>Implements FR11, FR12, NFR-S1, D7 of add-agent-executor.
 */
public final class AgentInvocationOptions {

    private static final String MODEL_FLAG = "--model";

    private static final String ALLOWED_TOOLS_FLAG = "--allowedTools";

    private static final String DISALLOWED_TOOLS_FLAG = "--disallowedTools";

    private static final String MAX_TURNS_FLAG = "--max-turns";

    private static final String ALLOWED_TOOLS_KEY = "allowedTools";

    private static final String DISALLOWED_TOOLS_KEY = "disallowedTools";

    private static final String MAX_TURNS_KEY = "maxTurns";

    /**
     * Hard-wired read-only tool allowlist for judge rounds (FR12, NFR-S1, D7):
     * "Read/Grep/Glob-class tools" per the spec. A judge check's {@code
     * allowedTools} setting may only narrow this set — see {@link
     * #renderForJudge(String, Map)}.
     */
    private static final List<String> JUDGE_READ_ONLY_TOOLS = List.of("Read", "Grep", "Glob");

    private AgentInvocationOptions() {}

    /**
     * Renders {@code model} and the recognized entries of {@code settings}
     * into CLI flag tokens, in the fixed order {@code --model},
     * {@code --allowedTools}, {@code --disallowedTools}, {@code --max-turns}.
     *
     * @param model the pinned, non-blank model id ({@code executor.model} /
     *     {@code check.model()})
     * @param settings the opaque settings map; may be empty, never null
     * @return the rendered flag tokens, always starting with {@code --model}
     *     and the model id
     */
    public static List<String> render(String model, Map<String, Object> settings) {
        List<String> argv = new ArrayList<>();
        argv.add(MODEL_FLAG);
        argv.add(model);
        addToolListFlag(argv, ALLOWED_TOOLS_FLAG, settings.get(ALLOWED_TOOLS_KEY));
        addToolListFlag(argv, DISALLOWED_TOOLS_FLAG, settings.get(DISALLOWED_TOOLS_KEY));
        addMaxTurnsFlag(argv, settings.get(MAX_TURNS_KEY));
        return argv;
    }

    /**
     * Renders the same flags as {@link #render(String, Map)}, plus a
     * hard-wired, non-configurable pinpoint {@code Write} allowance for
     * {@code decisionFilePath} appended to the {@code --allowedTools} list
     * (design D7, closing the permission risk noted in the Risks section of
     * design.md): the executor round must always be able to write its
     * decision file, regardless of whatever the stage's {@code
     * allowedTools} setting does or does not contain. This entry never comes
     * from the manifest settings map — it is adapter policy for the
     * executor round only; the judge's read-only hard-wiring (task 7.2) is a
     * separate, sibling concern.
     *
     * @param model the pinned, non-blank model id ({@code executor.model})
     * @param settings the opaque settings map; may be empty, never null
     * @param decisionFilePath this round's decision-file path, as created by
     *     {@link DecisionFileTransport#open()}; never null
     * @return the rendered flag tokens, always starting with {@code --model}
     *     and always containing an {@code --allowedTools} flag whose value
     *     ends with {@code Write(<decisionFilePath>)}
     */
    public static List<String> renderForExecutor(String model, Map<String, Object> settings, Path decisionFilePath) {
        List<String> argv = new ArrayList<>();
        argv.add(MODEL_FLAG);
        argv.add(model);
        argv.add(ALLOWED_TOOLS_FLAG);
        argv.add(withWriteAllowance(settings.get(ALLOWED_TOOLS_KEY), decisionFilePath));
        addToolListFlag(argv, DISALLOWED_TOOLS_FLAG, settings.get(DISALLOWED_TOOLS_KEY));
        addMaxTurnsFlag(argv, settings.get(MAX_TURNS_KEY));
        return argv;
    }

    /**
     * Renders the same flags as {@link #render(String, Map)}, except {@code
     * --allowedTools} is hard-wired policy rather than a pass-through of the
     * settings map (FR12, NFR-S1, D7): the judge runs strictly read-only. With
     * no {@code allowedTools} setting, the effective set is the full read-only
     * default ({@link #JUDGE_READ_ONLY_TOOLS}). With an {@code allowedTools}
     * setting present, the effective set is the intersection of the manifest
     * list and the read-only default, in the manifest's order — the setting
     * may only narrow the read-only set, never widen it; any write-capable
     * tool it names (e.g. {@code Write}, {@code Edit}, {@code Bash}) is
     * silently dropped, never included regardless of what the manifest
     * requests. If the intersection is empty (the manifest requested only
     * write-capable tools), the {@code --allowedTools} flag is omitted
     * entirely, consistent with {@link #render(String, Map)}'s existing
     * empty-list handling.
     *
     * <p>No separate hard-wired {@code --disallowedTools} is added here: the
     * {@code allowedTools} intersection above already makes it impossible for
     * the effective set to contain a write-capable tool, so a defensive
     * disallow-list would be redundant — {@code disallowedTools} still
     * renders normally as a pass-through of the manifest setting, same as
     * {@link #render(String, Map)}.
     *
     * @param model the pinned, non-blank model id ({@code check.model()})
     * @param settings the opaque settings map; may be empty, never null
     * @return the rendered flag tokens, always starting with {@code --model}
     */
    public static List<String> renderForJudge(String model, Map<String, Object> settings) {
        List<String> argv = new ArrayList<>();
        argv.add(MODEL_FLAG);
        argv.add(model);
        addToolListFlag(argv, ALLOWED_TOOLS_FLAG, effectiveJudgeAllowedTools(settings.get(ALLOWED_TOOLS_KEY)));
        addToolListFlag(argv, DISALLOWED_TOOLS_FLAG, settings.get(DISALLOWED_TOOLS_KEY));
        addMaxTurnsFlag(argv, settings.get(MAX_TURNS_KEY));
        return argv;
    }

    private static List<String> effectiveJudgeAllowedTools(@Nullable Object rawAllowedTools) {
        if (!(rawAllowedTools instanceof List<?> requested)) {
            return JUDGE_READ_ONLY_TOOLS;
        }
        Set<String> narrowed = new LinkedHashSet<>();
        for (Object tool : requested) {
            if (JUDGE_READ_ONLY_TOOLS.contains(tool)) {
                narrowed.add((String) tool);
            }
        }
        return List.copyOf(narrowed);
    }

    private static String withWriteAllowance(@Nullable Object rawAllowedTools, Path decisionFilePath) {
        StringBuilder joined = new StringBuilder();
        if (rawAllowedTools instanceof List<?> tools) {
            for (Object tool : tools) {
                joined.append(tool).append(',');
            }
        }
        joined.append("Write(").append(decisionFilePath).append(')');
        return joined.toString();
    }

    private static void addToolListFlag(List<String> argv, String flag, @Nullable Object rawValue) {
        if (!(rawValue instanceof List<?> tools) || tools.isEmpty()) {
            return;
        }
        StringBuilder joined = new StringBuilder();
        for (Object tool : tools) {
            if (!joined.isEmpty()) {
                joined.append(',');
            }
            joined.append(tool);
        }
        argv.add(flag);
        argv.add(joined.toString());
    }

    private static void addMaxTurnsFlag(List<String> argv, @Nullable Object rawValue) {
        if (!(rawValue instanceof Number maxTurns)) {
            return;
        }
        argv.add(MAX_TURNS_FLAG);
        argv.add(Long.toString(maxTurns.longValue()));
    }
}
