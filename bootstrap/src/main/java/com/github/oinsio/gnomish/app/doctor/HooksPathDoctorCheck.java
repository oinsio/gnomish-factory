package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.adapter.git.HooksPathLeftover;
import com.github.oinsio.gnomish.adapter.git.HooksPathVerdict;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * The hooks-path line earlier factory versions left in the registered clone's own configuration
 * (FR8 of add-doctor-command; UX2 of own-git-invocation-policy): {@link HooksPathLeftover#assess},
 * which reads the clone's local configuration through the factory's git runner and alone knows
 * which value is the factory's (design D6). A leftover warns with the owner's unset line; nothing
 * is removed (NG2). Skipped when no project resolves (FR2).
 *
 * <p>Implements FR4, FR8, NFR-O1 of add-doctor-command; UX2 of own-git-invocation-policy.
 */
@NullMarked
@Component
final class HooksPathDoctorCheck implements DoctorCheck {

    private final HooksPathLeftover leftover;

    /** @param leftover the owner, over the factory's git runner with the invocation policy in force */
    HooksPathDoctorCheck(HooksPathLeftover leftover) {
        this.leftover = leftover;
    }

    @Override
    public DoctorCheckId id() {
        return DoctorCheckId.HOOKS_PATH;
    }

    @Override
    public List<DoctorVerdict> run(DoctorContext context) {
        return context.onClone(
                id(),
                clone -> List.of(
                        switch (leftover.assess(clone.clonePath())) {
                            case HooksPathVerdict.Clear clear ->
                                new DoctorVerdict(
                                        id(), DoctorStatus.OK, "no hooks path an earlier factory version wrote", null);
                            case HooksPathVerdict.Leftover found ->
                                new DoctorVerdict(
                                        id(),
                                        DoctorStatus.WARN,
                                        "the clone's local configuration still holds the hooks path an earlier factory"
                                                + " version wrote: "
                                                + found.value().forConsole(),
                                        HooksPathVerdict.FIX);
                            case HooksPathVerdict.Unread unread ->
                                new DoctorVerdict(
                                        id(),
                                        DoctorStatus.WARN,
                                        "the clone's local hooks path could not be read: "
                                                + unread.reason().forConsole(),
                                        "run git -C " + clone.clonePath() + " config --local --get core.hooksPath to"
                                                + " see what git answers, fix what it names, then run gnomish"
                                                + " doctor again");
                        }));
    }
}
