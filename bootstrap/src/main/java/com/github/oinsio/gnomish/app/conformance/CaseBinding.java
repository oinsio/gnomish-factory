package com.github.oinsio.gnomish.app.conformance;

import com.github.oinsio.gnomish.executorcontract.Executor;
import java.nio.file.Path;
import org.jspecify.annotations.NullMarked;

/**
 * How an in-process candidate binds its executor for one case (design D18 of
 * migrate-verify-to-executor-contract, "The conformance kit drives a built-in through its entry"): the
 * case and its own working copy in, a new executor out — the binding the built-in's registry entry
 * makes at resolution, so the executor judges the working copy the kit names. A spec chooses the
 * domain workspace the working copy stands for, an attempt commit included.
 *
 * <p>Implements FR1, FR8, FR13 of migrate-verify-to-executor-contract.
 */
@NullMarked
public interface CaseBinding {

    /**
     * The executor of one case.
     *
     * @param kase the case being run; never null
     * @param workingCopy the case's own working copy, an existing empty directory; never null
     * @return the executor bound to that working copy; never null
     */
    Executor bind(KitCase kase, Path workingCopy);
}
