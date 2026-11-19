package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.check.http.HttpCheckSubsectionValidator;
import com.github.oinsio.gnomish.adapter.check.http.HttpExecutor;
import com.github.oinsio.gnomish.adapter.scm.ScmConnectionReader;
import com.github.oinsio.gnomish.adapter.scm.actions.GithubCheckSubsectionValidator;
import com.github.oinsio.gnomish.adapter.scm.builtin.ScmBuiltIns;
import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The operator's {@code factory.check.<built-in>} subsections, read by core for the built-in verdict
 * executors (task 5.2 of migrate-verify-to-executor-contract; FR4, FR16, FR17 of
 * add-plugin-architecture): the one owner of two duties the check-provider seam carried until the
 * check SPI left — grading the subsections at startup, and the credential names the run's scrub and
 * never-allowlist sets take from the built-ins.
 *
 * <p><b>Grading.</b> Only the built-ins that take a subsection may have one: {@code github-actions}
 * and {@code scm-status} (their connection, read at run assembly by {@link ScmConnectionReader}) and
 * {@code http} (none, by definition — {@link HttpCheckSubsectionValidator} reports any key). Any
 * other key is a located error naming the three, and a {@code connection:} reference is graded
 * against the operator's profiles, so a malformed subsection fails startup with every problem
 * listed rather than surfacing as a parked task.
 *
 * <p><b>Credential names.</b> The token name of each SCM built-in the operator connected — its
 * describe's credential, or the name its subsection or profile renames it to ({@link
 * ScmBuiltIns#credential(String, Map)}, the name the token is resolved under) — and, per law, the
 * token name of each SCM built-in a check names, connected or not, and the credential each {@code
 * http} check names in its own {@code auth} setting ({@link HttpExecutor#credentials}), which its
 * describe cannot declare. These are the names the run's secrets are read under too, so {@code
 * doctor} asks after exactly them: a built-in neither connected nor named declares nothing. A name in the union is scrubbed from every child environment and refused
 * as a passthrough name (NFR-S1); a {@code program} executor's names are deliberately not here, they
 * are its own grant (design D10 of define-executor-contract).
 *
 * <p>Implements FR4, FR16, FR17 of add-plugin-architecture; FR13, NFR-S1, NFR-S2 of
 * migrate-verify-to-executor-contract.
 */
public final class BuiltInCheckSubsections {

    /** The file a located subsection error is reported against: operator configuration. */
    static final String OPERATOR_CONFIG_FILE = "application.yaml";

    /** The built-ins whose token is named by a connection, in the order an operator reads them. */
    private static final List<String> SCM = List.of(BuiltInExecutors.GITHUB_ACTIONS, BuiltInExecutors.SCM_STATUS);

    /** The built-ins that take a {@code factory.check} subsection. */
    private static final List<String> CONFIGURABLE =
            List.of(BuiltInExecutors.GITHUB_ACTIONS, BuiltInExecutors.HTTP, BuiltInExecutors.SCM_STATUS);

    private final Map<String, Map<String, Object>> configured;
    private final ConnectionProfiles profiles;

    /**
     * @param configured the operator's raw {@code factory.check} subsections, keyed by built-in name;
     *     never null
     * @param profiles the operator's {@code factory.connections} profiles; never null
     */
    public BuiltInCheckSubsections(Map<String, Map<String, Object>> configured, ConnectionProfiles profiles) {
        this.configured = Map.copyOf(configured);
        this.profiles = profiles;
    }

    /**
     * Every located problem of the configured subsections, in key order.
     *
     * @return the problems; empty when every subsection is valid
     */
    public List<ConfigError> validate() {
        List<ConfigError> errors = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : new TreeMap<>(configured).entrySet()) {
            String where = ScmConnectionReader.PREFIX + entry.getKey();
            if (!CONFIGURABLE.contains(entry.getKey())) {
                errors.add(new ConfigError(
                        OPERATOR_CONFIG_FILE,
                        where,
                        "no built-in reads factory.check.%s; the built-ins configured here: %s"
                                .formatted(entry.getKey(), CONFIGURABLE)));
                continue;
            }
            errors.addAll(profiles.validateReference(OPERATOR_CONFIG_FILE, where, entry.getValue()));
            errors.addAll(gradeContent(entry.getKey(), where, profiles.resolve(entry.getValue())));
        }
        return List.copyOf(errors);
    }

    /**
     * Fails on any located subsection problem, listing them all (NFR-R1 of add-plugin-architecture):
     * the composition root calls it once, so a malformed subsection stops startup.
     *
     * @throws IllegalStateException naming every located problem
     */
    public void requireValid() {
        List<ConfigError> errors = validate();
        if (!errors.isEmpty()) {
            throw new IllegalStateException("invalid check configuration:"
                    + errors.stream()
                            .map(e -> System.lineSeparator() + "  " + e.render())
                            .collect(Collectors.joining()));
        }
    }

    /**
     * The installation's credential names: the token name of each SCM built-in the operator
     * connected, as its subsection or profile names it or by its default.
     *
     * @return the names, without duplicates; never null
     */
    public List<String> credentialNames() {
        Set<String> names = new LinkedHashSet<>();
        for (String builtIn : SCM) {
            if (configured.containsKey(builtIn)) {
                names.add(tokenName(builtIn));
            }
        }
        return List.copyOf(names);
    }

    /**
     * The credential names of a run of {@code definition}: the installation's, the token name of each
     * SCM built-in a check names, and the credential every {@code http} check names in its own
     * settings.
     *
     * @param definition the run's law; never null
     * @return the names in installation-then-check order, without duplicates; never null
     */
    public List<String> credentialNames(PipelineDefinition definition) {
        Set<String> names = new LinkedHashSet<>(credentialNames());
        for (StageDefinition stage : definition.stages()) {
            for (VerifyCheck check : stage.verify()) {
                if (check instanceof VerifyCheck.Executor executor) {
                    names.addAll(checkCredentials(executor));
                }
            }
        }
        return List.copyOf(names);
    }

    private List<String> checkCredentials(VerifyCheck.Executor check) {
        String name = check.executorName();
        if (name.equals(BuiltInExecutors.HTTP)) {
            return HttpExecutor.credentials(check);
        }
        return SCM.contains(name) ? List.of(tokenName(name)) : List.of();
    }

    private String tokenName(String builtIn) {
        return ScmBuiltIns.credential(builtIn, profiles.resolve(configured.getOrDefault(builtIn, Map.of())));
    }

    /**
     * The built-in's own grading of its subsection's content, once the {@code connection:} reference
     * is resolved — so one set of key rules grades an inline and a referencing subsection alike, while
     * the reference itself is graded above. {@code scm-status} grades no content of its own.
     */
    private static List<ConfigError> gradeContent(String key, String where, Map<String, Object> resolved) {
        return switch (key) {
            case BuiltInExecutors.GITHUB_ACTIONS ->
                new GithubCheckSubsectionValidator().validate(OPERATOR_CONFIG_FILE, where, resolved);
            case BuiltInExecutors.HTTP ->
                new HttpCheckSubsectionValidator().validate(OPERATOR_CONFIG_FILE, where, resolved);
            default -> List.of();
        };
    }
}
