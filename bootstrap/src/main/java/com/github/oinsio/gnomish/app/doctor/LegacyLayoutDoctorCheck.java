package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.app.project.FactoryHome;
import com.github.oinsio.gnomish.app.project.LegacyEntry;
import java.io.UncheckedIOException;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * Leftovers of the pre-registry folder layout, a warning each (FR1 of add-doctor-command): {@link
 * FactoryHome#legacyFolders()}, the only code that knows the old names, so the check lists no
 * folder name itself (design D4). Host-level: it runs with or without a project. A home it cannot
 * list stays a warning, as every line of this check is, with the owner's words rendered for the
 * console (NFR-O1) and the folder to make listable (UX1).
 *
 * <p>Implements FR1, FR4, NFR-O1, UX1 of add-doctor-command.
 */
@NullMarked
@Component
final class LegacyLayoutDoctorCheck implements DoctorCheck {

    private final FactoryHome home;

    /** @param home the factory home the configuration loader resolved */
    LegacyLayoutDoctorCheck(FactoryHome home) {
        this.home = home;
    }

    @Override
    public DoctorCheckId id() {
        return DoctorCheckId.LEGACY_LAYOUT;
    }

    @Override
    public List<DoctorVerdict> run(DoctorContext context) {
        List<LegacyEntry> entries;
        try {
            entries = home.legacyFolders();
        } catch (UncheckedIOException e) {
            return List.of(new DoctorVerdict(
                    id(),
                    DoctorStatus.WARN,
                    "the factory home could not be inspected: " + DoctorText.words(e),
                    "make " + home.root() + " and its folders listable by the factory's user (ls -la " + home.root()
                            + "), then run gnomish doctor again"));
        }
        if (entries.isEmpty()) {
            return List.of(new DoctorVerdict(id(), DoctorStatus.OK, "no leftover in " + home.root(), null));
        }
        return entries.stream()
                .map(entry -> new DoctorVerdict(
                        id(),
                        DoctorStatus.WARN,
                        entry.path() + " is a leftover of the pre-registry layout; its state now lives in "
                                + entry.successor(),
                        "move what you still need to " + entry.successor() + ", then remove " + entry.path()))
                .toList();
    }
}
