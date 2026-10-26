package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.adapter.git.GitVersionCheck;
import com.github.oinsio.gnomish.adapter.git.GitVersionVerdict;
import com.github.oinsio.gnomish.gittransfer.GitVersion;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * The git version floor (FR1, FR7 of add-doctor-command): the verdict of {@link
 * GitVersionCheck#assess()}, the decision the startup refusal {@link GitVersionCheck#verify()} is
 * built on (design D1), never a second reading of {@code git --version}; the floor and its reason
 * are {@link GitVersion}'s own.
 *
 * <p>Implements FR1, FR4, FR7, NFR-O1 of add-doctor-command.
 */
@NullMarked
@Component
final class GitFloorDoctorCheck implements DoctorCheck {

    private final GitVersionCheck gitVersionCheck;

    /** @param gitVersionCheck the git floor owner the startup path refuses through */
    GitFloorDoctorCheck(GitVersionCheck gitVersionCheck) {
        this.gitVersionCheck = gitVersionCheck;
    }

    @Override
    public DoctorCheckId id() {
        return DoctorCheckId.GIT_FLOOR;
    }

    @Override
    public List<DoctorVerdict> run(DoctorContext context) {
        String fix = "install git " + GitVersion.FLOOR + " or later on the factory's PATH";
        return List.of(
                switch (gitVersionCheck.assess()) {
                    case GitVersionVerdict.Meets meets ->
                        new DoctorVerdict(
                                id(),
                                DoctorStatus.OK,
                                "git " + meets.installed() + " meets the floor " + GitVersion.FLOOR,
                                null);
                    case GitVersionVerdict.Below below ->
                        new DoctorVerdict(
                                id(),
                                DoctorStatus.FAIL,
                                "git " + below.installed() + " is below the floor " + GitVersion.FLOOR + ": "
                                        + GitVersion.FLOOR_REASON,
                                fix);
                    case GitVersionVerdict.Unreported unreported ->
                        new DoctorVerdict(
                                id(),
                                DoctorStatus.FAIL,
                                "git reported no version (it printed: "
                                        + unreported.printed().forConsole() + ")",
                                fix);
                });
    }
}
