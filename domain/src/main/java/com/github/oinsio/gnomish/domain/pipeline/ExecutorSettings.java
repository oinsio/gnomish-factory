package com.github.oinsio.gnomish.domain.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The settings a {@code program} executor receives for one stage, merged once (FR9 of
 * define-executor-contract; obligation 4 of its task 7.1): the declaration's {@code
 * executors.<name>.settings}, then the stage's own {@code executor.settings} over them key by key,
 * then the stage's {@code executor.model}, when present, under the key {@link #MODEL_KEY} — the
 * model "is carried to the executor as a setting" (pipeline-config, "Program stage may omit the
 * model"). The merge is shallow: a stage key replaces the declaration's value whole.
 *
 * <p>This class is the one owner of that merge: the describe preflight judges exactly these values
 * against the executor's settings schema, and the request a round sends carries exactly these
 * values, so the judged map is the sent map by construction. Each top-level key remembers which law
 * file set it ({@link #originOf}), so a violation is located where the operator wrote the value.
 *
 * <p>Pure, immutable, compared by content.
 *
 * <p>Implements FR9, UX1 of define-executor-contract.
 */
public final class ExecutorSettings {

    /** The key the stage's model is carried under. */
    public static final String MODEL_KEY = "model";

    /** Which law file set a top-level settings key. */
    public enum Origin {
        /** {@code executors.<name>.settings} in {@code config.yaml}. */
        DECLARATION,
        /** The stage manifest's {@code executor.settings}. */
        STAGE,
        /** The stage manifest's {@code executor.model}. */
        MODEL
    }

    private final Map<String, Object> values;
    private final Map<String, Origin> origins;

    private ExecutorSettings(Map<String, Object> values, Map<String, Origin> origins) {
        this.values = Map.copyOf(values);
        this.origins = Map.copyOf(origins);
    }

    /**
     * The settings of a declaration no stage names: its own, unmerged.
     *
     * @param declaration the executor declaration; never null
     * @return the settings; never null
     */
    public static ExecutorSettings of(ExecutorDeclaration declaration) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Origin> origins = new LinkedHashMap<>();
        put(values, origins, declaration.settings(), Origin.DECLARATION);
        return new ExecutorSettings(values, origins);
    }

    /**
     * The settings {@code stage} sends {@code declaration}'s executor.
     *
     * @param declaration the executor declaration the stage names; never null
     * @param stage the stage's executor mechanism; never null
     * @return the merged settings; never null
     */
    public static ExecutorSettings of(ExecutorDeclaration declaration, StageDefinition.Executor stage) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Origin> origins = new LinkedHashMap<>();
        put(values, origins, declaration.settings(), Origin.DECLARATION);
        put(values, origins, stage.settings(), Origin.STAGE);
        if (!stage.model().isBlank()) {
            put(values, origins, Map.of(MODEL_KEY, stage.model()), Origin.MODEL);
        }
        return new ExecutorSettings(values, origins);
    }

    private static void put(
            Map<String, Object> values, Map<String, Origin> origins, Map<String, Object> layer, Origin origin) {
        layer.forEach((key, value) -> {
            values.put(key, value);
            origins.put(key, origin);
        });
    }

    /** The merged values, as plain JDK types; immutable. */
    public Map<String, Object> values() {
        return values;
    }

    /**
     * Which law file set the top-level key {@code path} starts with — the part before the first
     * {@code .} or {@code [}. A key no layer set (a property a schema requires and nobody wrote) is
     * the declaration's to supply.
     *
     * @param path a settings path as the schema judge spells it ({@code maxTurns}, {@code a.b},
     *     {@code list[0]}); never null
     * @return the origin; never null
     */
    public Origin originOf(String path) {
        int end = path.length();
        for (int index = 0; index < path.length(); index++) {
            char c = path.charAt(index);
            if (c == '.' || c == '[') {
                end = index;
                break;
            }
        }
        return origins.getOrDefault(path.substring(0, end), Origin.DECLARATION);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ExecutorSettings that && values.equals(that.values) && origins.equals(that.origins);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values, origins);
    }

    @Override
    public String toString() {
        return "ExecutorSettings" + values;
    }
}
