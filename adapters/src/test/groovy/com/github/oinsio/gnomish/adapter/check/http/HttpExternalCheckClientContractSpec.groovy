package com.github.oinsio.gnomish.adapter.check.http

import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.engine.port.contract.ExternalCheckClientContract

/**
 * FR3, FR9 of add-plugin-architecture: the built-in http provider is one provider among the
 * discovered set, not a special case — so it passes the very port-contract suite every other
 * external-check adapter passes, over the same four {@link PollStatus} variants the engine's poll
 * loop switches on (FR14 of add-manual-run, metric M2).
 */
class HttpExternalCheckClientContractSpec extends ExternalCheckClientContract implements HttpCheckFixture {

    @Override
    protected Optional<PollStatus> arrange(PollVariant variant) {
        Optional.of(switch (variant) {
                    case PollVariant.PASS -> poll([url: URL], new HttpCheckFixture.ScriptedExchange(200, 'ok'))
                    case PollVariant.FAIL_WITH_FINDINGS -> poll([url: URL], new HttpCheckFixture.ScriptedExchange(500, 'boom'))
                    case PollVariant.RUNNING -> poll(
                            [url: URL, ('pending-when'): [('json-path'): 'state', equals: 'RUNNING']],
                            new HttpCheckFixture.ScriptedExchange(200, '{"state":"RUNNING"}'))
                    case PollVariant.CANNOT_VERIFY -> poll(
                            [url: URL], new HttpCheckFixture.ScriptedExchange(new IOException('unreachable')))
                })
    }

    private PollStatus poll(Map<String, Object> params, HttpCheckFixture.ScriptedExchange exchange) {
        new HttpExternalCheckClient(exchange, providing([:])).poll(check(params), null)
    }
}
