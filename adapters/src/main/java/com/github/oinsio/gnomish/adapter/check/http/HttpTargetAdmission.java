package com.github.oinsio.gnomish.adapter.check.http;

import com.github.oinsio.gnomish.domain.egress.EgressRefusedException;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.httpegress.EgressAdmission;
import com.github.oinsio.gnomish.httpegress.OperatorEgress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

/**
 * The load-time judgement of one {@code http} check's target (FR15, FR17, UX4, design D16 of
 * migrate-verify-to-executor-contract, the law-medium bullet): the target is the law's request, and
 * {@link EgressAdmission#request} decides it against the operator's {@code factory.egress.in-process}
 * scope with no network. A request nobody grants is a located load error at the check's {@code
 * url}, naming how to grant it — so a misconfigured manifest fails at load, not at its first poll.
 *
 * <p>Two targets are left to the connect-time guard, which judges every hop of every request
 * anyway ({@code EgressAllowlist}): a target whose authority carries a {@code ${…}} reference,
 * whose host only a run can know ({@link HttpCheckVariables#templatesAuthority}); and a {@code url}
 * that is absent, malformed or relative, which the params validator already reports at the same
 * place — one load problem, one error.
 *
 * <p>Implements FR15, FR17, UX4 of migrate-verify-to-executor-contract.
 */
public final class HttpTargetAdmission {

    private HttpTargetAdmission() {}

    /**
     * Judges the target of one {@code http} check.
     *
     * @param file the stage manifest the check is declared in
     * @param where the located settings of the check, e.g. {@code verify[0].params}
     * @param settings the check's settings as plain values; never null
     * @param operator the operator's {@code in-process} scope; never null
     * @return the located refusal, or an empty list when load admits the target or leaves it to
     *     connect
     */
    public static List<ConfigError> judge(
            String file, String where, Map<String, Object> settings, OperatorEgress operator) {
        String url = HttpCheckParams.string(settings, HttpCheckParams.URL_KEY);
        if (url == null || HttpCheckVariables.templatesAuthority(url)) {
            return List.of();
        }
        URI target;
        try {
            target = new URI(HttpCheckVariables.erase(url));
        } catch (URISyntaxException malformed) {
            return List.of();
        }
        if (!target.isAbsolute()) {
            return List.of();
        }
        try {
            EgressAdmission.request(target, operator);
            return List.of();
        } catch (EgressRefusedException refused) {
            return List.of(new ConfigError(
                    file,
                    where + "." + HttpCheckParams.URL_KEY,
                    refused.refusal().describe()));
        }
    }
}
