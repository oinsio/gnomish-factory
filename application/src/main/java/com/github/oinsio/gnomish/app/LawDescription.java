package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.List;

/**
 * What {@link RunAssembly#describe} decided for a run's law (design D5 of define-executor-contract,
 * as revised 2026-10-31): the law described, ready to assemble, or every located error that parks
 * the task as an invalid law does — a program that could not be described, an external failure
 * running it, or an answer that disagrees with the law.
 *
 * <p>Implements FR4, FR13, UX1 of define-executor-contract.
 */
public sealed interface LawDescription permits DescribedLaw, LawDescription.Refused {

    /**
     * The law could not be described.
     *
     * @param errors the located errors, in declaration order; never empty
     */
    record Refused(List<ConfigError> errors) implements LawDescription {

        public Refused {
            errors = List.copyOf(errors);
        }
    }
}
