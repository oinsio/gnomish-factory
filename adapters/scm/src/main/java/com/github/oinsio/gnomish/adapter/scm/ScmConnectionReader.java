package com.github.oinsio.gnomish.adapter.scm;

import com.github.oinsio.gnomish.app.ConnectionProfiles;
import com.github.oinsio.gnomish.domain.egress.ConnectionEndpoint;
import com.github.oinsio.gnomish.domain.egress.EgressMedium;
import com.github.oinsio.gnomish.domain.egress.EgressRefusedException;
import com.github.oinsio.gnomish.domain.egress.EndpointDeclaration;
import com.github.oinsio.gnomish.httpegress.EgressAdmission;
import com.github.oinsio.gnomish.httpegress.OperatorEgress;
import java.util.List;
import java.util.Map;

/**
 * The one reader of a credentialed built-in's operator connection, {@code factory.check.<name>}
 * (FR17, NFR-S2, design D16 of migrate-verify-to-executor-contract): the {@code github-actions} and
 * {@code scm-status} built-ins both take their endpoint from here, never from a subsection read of
 * their own.
 *
 * <p>Both forms of the subsection are the operator's — inline, or a {@code connection:} reference to
 * a {@code factory.connections} profile — so the endpoint is minted through {@link
 * ConnectionProfiles#endpoint} with the operator medium, its host is granted, and the connection's
 * {@code egress} list travels with it. It is then admitted through {@link EgressAdmission}, with the
 * built-in's describe {@code egress} and the operator's {@code in-process} scope joining its
 * admitted set (design D16, "A redirect target is an egress target"), and every refusal names the
 * consumer's key under {@code factory.check.<name>}. Run assembly reads the connections of the
 * built-ins the law's checks name, once per run and before the describe branch decides (design D18,
 * "Where a refusal at run assembly goes"), so a refusal is the task's: a located error on the
 * describe refusal's path, which parks the task in {@code take} and {@code serve} and fails a {@code
 * run} as an invalid law does — never a startup failure, never a daemon condition.
 *
 * <p>Implements FR17, NFR-S2 of migrate-verify-to-executor-contract.
 */
public final class ScmConnectionReader {

    /** The operator subtree every built-in's subsection sits under. */
    public static final String PREFIX = "factory.check.";

    private final ConnectionProfiles profiles;
    private final OperatorEgress operator;

    /**
     * @param profiles the operator's {@code factory.connections} profiles; never null
     * @param operator the operator's {@code in-process} scope; never null
     */
    public ScmConnectionReader(ConnectionProfiles profiles, OperatorEgress operator) {
        this.profiles = profiles;
        this.operator = operator;
    }

    /**
     * Reads and admits the connection of the built-in {@code name}.
     *
     * @param name the built-in's name, the key of its subsection; never blank
     * @param subsection the raw {@code factory.check.<name>} subsection, before profile resolution;
     *     never null
     * @param declaration the built-in's endpoint key and vendor default host; never null
     * @param declaredEgress the built-in's describe {@code egress}; never null
     * @return the admitted connection; never null
     * @throws IllegalArgumentException when neither the subsection nor its profile names the endpoint
     * @throws EgressRefusedException when the guard refuses the endpoint, naming the key
     */
    public ScmConnection read(
            String name, Map<String, Object> subsection, EndpointDeclaration declaration, List<String> declaredEgress) {
        String where = PREFIX + name;
        ConnectionEndpoint endpoint = profiles.endpoint(subsection, declaration.key(), EgressMedium.OPERATOR)
                .orElseThrow(() -> new IllegalArgumentException(where + "." + declaration.key() + " is required"));
        return new ScmConnection(
                EgressAdmission.admit(endpoint, declaration.under(where), operator, declaredEgress),
                profiles.resolve(subsection));
    }
}
