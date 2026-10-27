package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.app.DoctorProjectReadings;
import com.github.oinsio.gnomish.app.SecretLocation;
import com.github.oinsio.gnomish.app.SecretLocator;
import com.github.oinsio.gnomish.app.UsageException;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * The secrets the tracker and the checks declare (FR1 of add-doctor-command): one result per
 * declared name — the tracker adapter's, the configured check providers' and the pipeline's own
 * checks', as the run assembly declares them — each located by the secrets adapter's own search.
 * Names and places only, never a value (NFR-S1). An owner's refusal — the tracker wiring's
 * unregistered tracker type, the secrets adapter's refusal of a name or of a file's permissions —
 * is the line in the owner's words, rendered for the console (NFR-O1), with its fix (UX1). Skipped
 * when no project resolves or the law did not load.
 *
 * <p>Implements FR1, FR4, NFR-O1, NFR-S1, UX1 of add-doctor-command.
 */
@NullMarked
@Component
final class SecretsDoctorCheck implements DoctorCheck {

    private final DoctorProjectReadings readings;
    private final SecretLocator locator;

    /**
     * @param readings the owners the declared names are read through
     * @param locator the secrets adapter's own search, answering where, never what
     */
    SecretsDoctorCheck(DoctorProjectReadings readings, SecretLocator locator) {
        this.readings = readings;
        this.locator = locator;
    }

    @Override
    public DoctorCheckId id() {
        return DoctorCheckId.SECRETS;
    }

    @Override
    public List<DoctorVerdict> run(DoctorContext context) {
        return context.onDefinition(id(), (clone, definition) -> declared(definition));
    }

    private List<DoctorVerdict> declared(PipelineDefinition definition) {
        List<String> names;
        try {
            names = readings.declaredSecrets(definition);
        } catch (UsageException e) {
            return List.of(
                    new DoctorVerdict(id(), DoctorStatus.FAIL, DoctorText.words(e), DoctorText.TRACKER_TYPE_FIX));
        }
        if (names.isEmpty()) {
            return List.of(new DoctorVerdict(id(), DoctorStatus.OK, "no secret is declared", null));
        }
        return names.stream().map(this::located).toList();
    }

    private DoctorVerdict located(String name) {
        try {
            return switch (locator.locate(name)) {
                case SecretLocation.Found found ->
                    new DoctorVerdict(id(), DoctorStatus.OK, name + " found in " + found.place(), null);
                case SecretLocation.Absent absent ->
                    new DoctorVerdict(
                            id(),
                            DoctorStatus.FAIL,
                            name + " not found; searched " + String.join(", ", absent.searched()),
                            "provide " + name + " in one of the places searched");
            };
        } catch (UsageException e) {
            return new DoctorVerdict(id(), DoctorStatus.FAIL, DoctorText.words(e), DoctorText.AS_THE_LINE_NAMES);
        }
    }
}
