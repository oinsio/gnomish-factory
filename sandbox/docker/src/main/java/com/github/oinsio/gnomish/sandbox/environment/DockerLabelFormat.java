package com.github.oinsio.gnomish.sandbox.environment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses the raw {@code k1=v1,k2=v2} string {@code docker ... --format '{{.Labels}}'} emits into
 * a label map the sweep-lifecycle evaluator classifies objects by. Splitting on bare commas and
 * equals signs is safe here specifically because every value the factory itself ever writes
 * (`true`, a sanitized environment key, {@code tracked}/{@code manual}, a project identity) is
 * drawn from a restricted, comma-and-equals-free alphabet, enforced at each value's own
 * construction seam ({@link com.github.oinsio.gnomish.app.git.TaskIdSanitizer}, {@link
 * OwnershipMode#label()}, {@code ProjectIdentity} — which rejects an operator override outside
 * {@code [A-Za-z0-9._-]+} precisely so one label value cannot forge a second label pair here).
 * This is not a general Docker label parser.
 */
final class DockerLabelFormat {

    private DockerLabelFormat() {}

    /**
     * Parses one object's raw label string.
     *
     * @param raw the {@code --format '{{.Labels}}'} output for one object; blank for an
     *     unlabelled object
     * @return the parsed label map; never null, empty for a blank input or an entry with no
     *     {@code =}
     */
    static Map<String, String> parse(String raw) {
        Map<String, String> labels = new LinkedHashMap<>();
        // No blank-input special case needed: split on a blank string yields one element with no
        // '=', which the loop below already skips, so a blank input falls through to the same
        // empty map by construction.
        for (String pair : raw.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                labels.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return labels;
    }
}
