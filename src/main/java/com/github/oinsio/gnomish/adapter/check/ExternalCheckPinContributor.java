package com.github.oinsio.gnomish.adapter.check;

import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.util.Set;

/**
 * The adapter half of the pin-check contract (FR16, design D10): the paths an
 * external-check adapter itself contributes to a check's pin set — a platform adapter
 * names its own definition file (GitHub Actions contributes the {@code checkId} workflow
 * file), while a human oracle has no repo-borne definition and contributes none. {@link
 * PinCheckedExternalCheckClient} unions this contribution with the stage law's declared
 * pin paths before the adapter's first poll.
 *
 * <p>Implements FR16 of add-sandbox-core.
 */
@FunctionalInterface
public interface ExternalCheckPinContributor {

    /**
     * The pin paths this adapter contributes for {@code check}; possibly empty, never
     * null.
     *
     * @param check the external check about to be polled; never null
     * @return repo-relative paths whose content defines the check for this adapter
     */
    Set<String> pinPaths(VerifyCheck.External check);

    /**
     * The empty contribution of adapters with no repo-borne definition (the interactive
     * client): with nothing declared in the law either, the pin passes vacuously (FR16).
     */
    static ExternalCheckPinContributor none() {
        return _ -> Set.of();
    }
}
