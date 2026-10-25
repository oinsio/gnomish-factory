package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.BoxGitVersionRefusedException;
import com.github.oinsio.gnomish.app.port.git.CloneRefFormatRefusedException;
import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeUnavailableException;
import com.github.oinsio.gnomish.app.port.run.ImageContractRefusedException;
import com.github.oinsio.gnomish.app.take.DaemonConditionKind;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.Optional;

/**
 * The one classifier of an operator precondition refusal (design D22 of add-sandbox-base-image):
 * whether a failure is one of the four refusals a check throws when the operator's setup does not
 * meet what the factory needs (design D18), and which. Every reader asks here — the claimed-run
 * dispatch for its typed result, {@code run}'s reporting and exit code, and the first-box step of
 * both fresh container paths for which failure leaves before the engine — so a fifth precondition
 * is one arm here, never a list per reader. {@code OperatorPreconditionOwnerSpec} in {@code
 * :bootstrap} keeps the four type names out of every other classification in this module.
 *
 * <p>Implements FR14, NFR-R2 of add-sandbox-base-image.
 */
public final class OperatorPreconditionRefusals {

    private OperatorPreconditionRefusals() {}

    /**
     * Classifies {@code failure}.
     *
     * @param failure what a command or a claimed run threw; never null
     * @return the precondition {@code failure} refuses, or empty when it is no operator precondition
     *     refusal; never {@link DaemonConditionKind#REMOTE}, which no check refuses
     */
    public static Optional<DaemonConditionKind> kindOf(Throwable failure) {
        return switch (failure) {
            case CloneRefFormatRefusedException ignored -> Optional.of(DaemonConditionKind.CLONE_REF_FORMAT);
            case ContainerRuntimeUnavailableException ignored -> Optional.of(DaemonConditionKind.CONTAINER_RUNTIME);
            case BoxGitVersionRefusedException ignored -> Optional.of(DaemonConditionKind.BOX_GIT_FLOOR);
            case ImageContractRefusedException ignored -> Optional.of(DaemonConditionKind.IMAGE_CONTRACT);
            default -> Optional.empty();
        };
    }

    /**
     * Classifies {@code failure} into the typed refusal a daemon condition opens on (design D19,
     * D21): the kind and the check's own message, which names the refusal and its fix.
     *
     * @param failure what a check, a probe or the janitor's pass threw; never null
     * @return the typed refusal, or empty when {@code failure} is no operator precondition refusal
     */
    public static Optional<TakeResult.OperatorPreconditionUnmet> unmet(Throwable failure) {
        // The refusal's message is the check's sentence around quotes it already neutralized, so it
        // is minted in the factory's family, as ClaimOutageRelease mints the slot's.
        return kindOf(failure)
                .map(kind -> new TakeResult.OperatorPreconditionUnmet(
                        UntrustedText.factory(String.valueOf(failure.getMessage())), kind));
    }
}
