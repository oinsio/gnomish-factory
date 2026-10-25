package com.github.oinsio.gnomish.app.take;

/**
 * The preconditions a daemon holds a condition on (design D19 of add-sandbox-base-image): the
 * remote the factory fetches from, which recovers by itself, and the four operator preconditions a
 * claimed run can meet after its claim, which the operator fixes. A run that meets one of the four
 * ends {@link TakeResult.OperatorPreconditionUnmet} naming its kind (design D18), and under {@code
 * serve} the kind is the condition the refusal opens.
 *
 * <p>This enum is the vocabulary's single owner: the typed result, the daemon's conditions and the
 * observability wire tokens all name a precondition by one of these constants.
 *
 * <p>Implements FR14, FR15 of add-sandbox-base-image.
 */
public enum DaemonConditionKind {

    /** Origin did not answer a fetch the daemon needed (the remote outage gate of FR9 of add-base-ref-resolution). */
    REMOTE,

    /** The container runtime a container-bound pipeline needs is unreachable. */
    CONTAINER_RUNTIME,

    /** The factory clone is not on the ref backend the factory's ref transactions need. */
    CLONE_REF_FORMAT,

    /** The sandbox image does not carry the image contract this factory was built with. */
    IMAGE_CONTRACT,

    /** The sandbox image's git is below the factory's git floor, or reports no version. */
    BOX_GIT_FLOOR
}
