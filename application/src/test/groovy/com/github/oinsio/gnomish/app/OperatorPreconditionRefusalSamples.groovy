package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.BoxGitVersionRefusedException
import com.github.oinsio.gnomish.app.port.git.CloneRefFormatRefusedException
import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeUnavailableException
import com.github.oinsio.gnomish.app.port.run.ImageContractRefusedException
import com.github.oinsio.gnomish.app.take.DaemonConditionKind
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * One sample refusal per operator precondition kind (design D22 of add-sandbox-base-image), so a
 * spec of a reader of {@link OperatorPreconditionRefusals} iterates the kinds rather than a
 * hand-listed subset: a kind with no sample fails {@link #of}.
 */
final class OperatorPreconditionRefusalSamples {

    private OperatorPreconditionRefusalSamples() {}

    /** The kinds a check refuses: every {@link DaemonConditionKind} but the remote. */
    static List<DaemonConditionKind> kinds() {
        DaemonConditionKind.values().findAll {
            it != DaemonConditionKind.REMOTE
        }
    }

    /** A fresh refusal of {@code kind}. */
    static RuntimeException of(DaemonConditionKind kind) {
        switch (kind) {
            case DaemonConditionKind.CLONE_REF_FORMAT:
                return new CloneRefFormatRefusedException('/clones/x', 'reftable', UntrustedText.subprocess('files'))
            case DaemonConditionKind.CONTAINER_RUNTIME:
                return new ContainerRuntimeUnavailableException('the Docker runtime is unreachable — start Docker')
            case DaemonConditionKind.BOX_GIT_FLOOR:
                return new BoxGitVersionRefusedException(
                'img:1', '2.56.0', UntrustedText.container('git version 2.43.0'), 'why')
            case DaemonConditionKind.IMAGE_CONTRACT:
                return new ImageContractRefusedException('img:1', null, 'io.gnomish.image-contract', 2, 'rebuild')
            default:
                throw new IllegalArgumentException("no sample refusal for ${kind}")
        }
    }
}
