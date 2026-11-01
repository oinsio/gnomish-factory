package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.describe.DescribeOutcome;
import com.github.oinsio.gnomish.domain.engine.DescribedExecutors;
import com.github.oinsio.gnomish.executorcontract.Describe;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The law a run is frozen from, described (design D5, D8, D11 of define-executor-contract): its
 * binding and the describe answer of every program executor it declares, as the describe preflight
 * obtained them. {@link RunAssembly#assemble} takes this and nothing narrower, so every run's engine
 * receives the versions the preflight obtained — the pin the round writer stamps and the pre-flight
 * compares against — every run's program dispatch is built from the answers the preflight agreed
 * with the law (task 7.3), and no run path can assemble without passing through {@link
 * RunAssembly#describe}. The engine's value is derived from the answers by the one derivation
 * ({@link DescribeOutcome.Described#executorsOf}), so the version a round pins and the describe its
 * program is built from cannot disagree.
 *
 * <p>The constructor is package-private: in production only the assembly's describe step builds
 * one ({@code DescribedLawOwnerSpec} in {@code :bootstrap} pins the one file that does).
 *
 * <p>Implements FR4, FR9, FR13 of define-executor-contract.
 */
public final class DescribedLaw implements LawDescription {

    private final LawBinding binding;
    private final Map<String, Describe> answers;
    private final DescribedExecutors executors;

    /**
     * @param binding which repository and tree the law is frozen from; never null
     * @param answers the describe answer of every described executor, by its law name; never null,
     *     empty when the law declares no executor or the run runs no stage
     */
    DescribedLaw(LawBinding binding, Map<String, Describe> answers) {
        this.binding = Objects.requireNonNull(binding, "binding");
        this.answers = Collections.unmodifiableMap(new LinkedHashMap<>(answers));
        this.executors = DescribeOutcome.Described.executorsOf(this.answers);
    }

    /** Which repository and tree the law is frozen from. */
    public LawBinding binding() {
        return binding;
    }

    /** The describe version and granted credential names of every described executor. */
    public DescribedExecutors executors() {
        return executors;
    }

    /**
     * The describe answer the preflight obtained for the executor declared as {@code name}.
     *
     * @param name the executor's law name; never null
     * @return its answer; empty when the preflight described no executor of that name
     */
    public Optional<Describe> answerOf(String name) {
        return Optional.ofNullable(answers.get(name));
    }
}
