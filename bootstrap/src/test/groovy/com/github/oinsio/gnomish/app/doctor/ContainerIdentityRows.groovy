package com.github.oinsio.gnomish.app.doctor

import com.github.oinsio.gnomish.app.ServePreconditions
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.run.ImageContractRefusedException
import com.github.oinsio.gnomish.app.port.run.SandboxImageReadiness
import com.github.oinsio.gnomish.app.take.DaemonConditionKind
import java.nio.file.Path

/**
 * The identity rows of the container-bound checks (M1 of add-doctor-command): the Docker runtime,
 * the sandbox image's contract and the guard image. The startup side is the startup owner of the
 * operator preconditions in its refusing form, {@code ServePreconditions.assess} — the verdict
 * startup classifies by kind before the first claim — and the doctor side asks the same owner,
 * built once per case over the same runtime and image fixture.
 *
 * <p>The image fixture answers the port's contract inspection and its assessment from one image
 * state: the assessment is the inspection followed by the box git floor probe, which holds here
 * (doctor never runs it, NG1). That split is pinned on the Docker adapter itself by {@code
 * DockerImageReadinessSpec}'s table: the assessment refuses on the contract exactly when the
 * inspection does.
 */
final class ContainerIdentityRows {

    private static final TaskBranchGit REFTABLE_CLONE = [requireRefFormat: { Path clone -> }] as TaskBranchGit

    private ContainerIdentityRows() {}

    static List<IdentityRow> all() {
        [
            new IdentityRow(DoctorCheckId.DOCKER, { Path dir ->
                [docker(true), docker(false)]
            }),
            new IdentityRow(DoctorCheckId.SANDBOX_IMAGE, { Path dir ->
                [
                    sandboxImage(null),
                    sandboxImage(contractRefusal())
                ]
            }),
            new IdentityRow(DoctorCheckId.GUARD_IMAGE, { Path dir ->
                [
                    guardImage(true),
                    guardImage(false)
                ]
            }),
        ]
    }

    private static IdentityRow.Case docker(boolean answers) {
        def preconditions = PreconditionsFixture.preconditions(REFTABLE_CLONE, image(null, true), answers)
        def refusal = startupRefusal(preconditions, DaemonConditionKind.CONTAINER_RUNTIME)
        def doctor = new DockerDoctorCheck(preconditions).run(DoctorSpecSupport.bound(true, false))
        IdentityRow.Case.refusing(answers ? 'a runtime that answers' : 'an unreachable runtime', refusal, doctor,
                refusal == null ? [] : [refusal.message])
    }

    private static IdentityRow.Case sandboxImage(ImageContractRefusedException contract) {
        def preconditions = PreconditionsFixture.preconditions(REFTABLE_CLONE, image(contract, true))
        def refusal = startupRefusal(preconditions, DaemonConditionKind.IMAGE_CONTRACT)
        def doctor = new SandboxImageDoctorCheck(preconditions, PreconditionsFixture.sandbox())
                .run(DoctorSpecSupport.bound(true, false))
        IdentityRow.Case.refusing(contract == null ? "the factory's contract" : 'another contract', refusal, doctor,
                refusal == null ? [] : [refusal.message])
    }

    // No startup path refuses on the guard image: a box pulls it on first use, and the startup
    // assessment never asks for it. The identity is that the startup assessment passes whether
    // the guard image is present or not, and doctor never fails on it — it warns when absent.
    private static IdentityRow.Case guardImage(boolean present) {
        def readiness = image(null, present)
        def preconditions = PreconditionsFixture.preconditions(REFTABLE_CLONE, readiness)
        def refusal = startupRefusal(preconditions, null)
        def doctor = new GuardImageDoctorCheck(readiness, PreconditionsFixture.sandbox()).run(DoctorSpecSupport.bound(true, false))
        assert doctor*.status() == [
            present ? DoctorStatus.OK : DoctorStatus.WARN
        ]
        IdentityRow.Case.refusing(present ? 'a present guard image' : 'an absent guard image', refusal, doctor, [])
    }

    /** What the startup assessment refused with, asserting the kind it classified; {@code null} when it passed. */
    private static RuntimeException startupRefusal(ServePreconditions preconditions, DaemonConditionKind expected) {
        def assessment = preconditions.assess(DoctorSpecSupport.CLONE, DoctorSpecSupport.definition())
        if (assessment.getClass().simpleName == 'Pass') {
            return null
        }
        assert assessment.kind() == expected
        assessment.refusal() as RuntimeException
    }

    /** One image state: its contract (refused, or {@code null} for the factory's), and the guard image present or absent. */
    private static SandboxImageReadiness image(ImageContractRefusedException contract, boolean guardPresent) {
        def inspect = { String image ->
            if (contract != null) {
                throw contract
            }
        }
        [
            inspectContract: inspect,
            assess: inspect,
            inspectPresence: { String image ->
                if (!guardPresent) {
                    throw new IllegalStateException("No such image: ${image}")
                }
            },
        ] as SandboxImageReadiness
    }

    private static ImageContractRefusedException contractRefusal() {
        new ImageContractRefusedException(PreconditionsFixture.IMAGE, null, 'io.gnomish.image-contract', 2, 'rebuild')
    }
}
