package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.app.FactoryVersion;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * The factory version and the Java runtime, informational (FR1 of add-doctor-command): the version
 * from {@link FactoryVersion}, its one runtime reader, and the runtime from the JDK's own {@link
 * Runtime#version()} — never a system property.
 *
 * <p>Implements FR1, FR4 of add-doctor-command.
 */
@NullMarked
@Component
final class FactoryVersionDoctorCheck implements DoctorCheck {

    @Override
    public DoctorCheckId id() {
        return DoctorCheckId.FACTORY_VERSION;
    }

    @Override
    public List<DoctorVerdict> run(DoctorContext context) {
        return List.of(new DoctorVerdict(
                id(),
                DoctorStatus.OK,
                "gnomish " + FactoryVersion.current().value() + " on Java " + Runtime.version(),
                null));
    }
}
