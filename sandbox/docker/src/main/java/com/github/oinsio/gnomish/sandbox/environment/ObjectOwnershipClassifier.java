package com.github.oinsio.gnomish.sandbox.environment;

import java.util.Map;

/**
 * Recovers {@link OwnershipMode} from a listed object's own labels (design: Migration of
 * add-serve-sandbox-lifecycle) — the read side of {@link FactoryDockerLabels#MODE_LABEL}, used by
 * the sweep-lifecycle policy to classify objects it did not itself create in this process.
 *
 * <p>An object created by a build that predates {@link FactoryDockerLabels#MODE_LABEL} carries no
 * mode label at all; an object whose label value this build does not recognize (e.g. a future
 * mode a newer build introduced) is likewise unreadable. Both classify as {@link
 * OwnershipMode#TRACKED} — the conservative choice, since {@code tracked} is governed by the
 * claim-heartbeat oracle (age-protected while a claim is fresh) whereas {@code manual} objects
 * have no oracle to protect them at all; misclassifying a manual object as tracked only makes it
 * harder to reap, never easier — while the reverse would strip its only protection. A mixed-version
 * host therefore degrades safely: nothing is destroyed just because it predates this label. This
 * covers objects that reach the sweep at all — one predating {@link
 * FactoryDockerLabels#PROJECT_LABEL} is outside the project-scoped listing entirely (FR8) and is
 * cleaned up by hand, per the operator guide.
 *
 * <p>Implements design: Migration of add-serve-sandbox-lifecycle.
 */
final class ObjectOwnershipClassifier {

    private ObjectOwnershipClassifier() {}

    /**
     * Classifies {@code labels} into the mode they name, falling back to {@link
     * OwnershipMode#TRACKED} for anything this build cannot read.
     *
     * @param labels the object's own Docker labels, as read back by {@code docker inspect}; never
     *     null
     * @return {@link OwnershipMode#MANUAL} when {@link FactoryDockerLabels#MODE_LABEL} is present
     *     and reads {@code manual}; {@link OwnershipMode#TRACKED} otherwise — including when the
     *     label is absent (a pre-upgrade object) or carries an unrecognized value
     */
    static OwnershipMode classify(Map<String, String> labels) {
        String value = labels.get(FactoryDockerLabels.MODE_LABEL);
        return OwnershipMode.MANUAL.label().equals(value) ? OwnershipMode.MANUAL : OwnershipMode.TRACKED;
    }
}
