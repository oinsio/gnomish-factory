package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.app.CheckClientFactory
import com.github.oinsio.gnomish.app.CheckParamsValidator
import com.github.oinsio.gnomish.app.CheckSubsectionValidator
import com.github.oinsio.gnomish.app.port.check.ExternalCheckPinContributor
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck

/**
 * A second, test-only check provider standing in for a third-party plugin jar (FR3, FR5 of
 * add-plugin-architecture). It exists to prove the check port has no github special case: this
 * factory is discovered, keyed, configured, validated and dispatched to through exactly the same
 * machinery, and its client passes the same {@code ExternalCheckClientContract} the real adapters do
 * ({@link PluginStandInCheckClientContractSpec}).
 *
 * <p>Nothing in core names this class — a spec stages its {@code META-INF/services} entry on a class
 * loader of its own, the way a plugin jar carries one.
 *
 * <p>The subsection it grades is one key, {@code endpoint}, and the credential it declares is named
 * by that subsection rather than by a constant — the connection-aware declaration of design D11,
 * which a compile-time constant in core could not have seen.
 */
class PluginStandInCheckClientFactory implements CheckClientFactory {

    /** The discriminator this stand-in registers under; deliberately not a vendor the build ships. */
    static final String PROVIDER = 'plugin-stand-in'

    /** The subsection key naming the credential — configuration data, not a constant (D11). */
    static final String CREDENTIAL_KEY = 'credential'

    /** The poll status the built client reports, settable per instance so the contract can drive all four. */
    PollStatus scripted = new PollStatus.Pass()

    @Override
    String provider() {
        PROVIDER
    }

    @Override
    ExternalCheckClient create(SecretsProvider secrets, Map<String, Object> subsection) {
        String credential = subsection[CREDENTIAL_KEY] as String
        if (credential != null) {
            secrets.find(credential)
                    .orElseThrow {
                        new IllegalStateException(credential + ' is required by ' + PROVIDER)
                    }
        }
        return new StandInClient(scripted)
    }

    @Override
    List<String> credentialEnvVars(Map<String, Object> subsection) {
        String credential = subsection[CREDENTIAL_KEY] as String
        credential == null ? List.<String> of() : List.of(credential)
    }

    @Override
    Optional<CheckSubsectionValidator> subsectionValidator() {
        Optional.of({ String file, String where, Map<String, Object> subsection ->
            subsection.containsKey('endpoint')
            ? List.<ConfigError> of()
            : List.of(new ConfigError(file, where + '.endpoint', "missing required key 'endpoint'"))
        } as CheckSubsectionValidator)
    }

    /**
     * The params validator this stand-in exposes, settable per instance so a spec can stage a
     * rejecting one; {@code null} stands for a provider that grades no params at all.
     */
    CheckParamsValidator params = { String file, String where, Map<String, Object> raw ->
        List.<ConfigError> of()
    } as CheckParamsValidator

    @Override
    Optional<CheckParamsValidator> paramsValidator() {
        Optional.ofNullable(params)
    }

    @Override
    ExternalCheckPinContributor pinContributor() {
        { VerifyCheck.External check -> ['stand-in/' + check.checkId()] as Set }
    }

    /** The stand-in's client: reports whatever status the factory was scripted with. */
    static class StandInClient implements ExternalCheckClient {

        private final PollStatus scripted

        StandInClient(PollStatus scripted) {
            this.scripted = scripted
        }

        @Override
        PollStatus poll(VerifyCheck.External check, Workspace workspace) {
            scripted
        }
    }

    /** The findings a scripted failure carries, so the contract's unmodifiability row has content. */
    static PollStatus failing() {
        new PollStatus.Fail(List.of(new Finding('stand-in finding', null, null)))
    }
}
