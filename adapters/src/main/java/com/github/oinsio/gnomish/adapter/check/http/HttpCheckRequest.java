package com.github.oinsio.gnomish.adapter.check.http;

import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import java.net.URI;
import java.net.http.HttpRequest;

/**
 * Composes one check's {@link HttpRequest} from its declared selectors, resolving the named
 * credential through {@link SecretsProvider} at request time (FR11, NFR-S1 of
 * add-plugin-architecture).
 *
 * <p>The secret exists only inside this call: it is read by name, written straight onto the request
 * header, and never held on a field, logged, or copied into a finding. What is committed to the
 * task branch is the name; what is sent is the value.
 *
 * <p>Implements FR11, NFR-S1 of add-plugin-architecture.
 */
final class HttpCheckRequest {

    private HttpCheckRequest() {}

    /**
     * Builds the request for one check, substituting the run's whitelisted variables into the url and
     * the declared headers (NFR-S2) before anything is composed from them.
     *
     * @param params the check's parsed selectors; never null
     * @param secrets the seam the named credential resolves through; never null
     * @param variables the run's whitelisted values; never null
     * @return the composed request, credential header included when one is declared
     * @throws HttpCheckCredentialException if a declared credential is missing or blank — fail
     *     closed, naming the secret, before any socket is opened
     * @throws HttpCheckVariableException if a {@code ${...}} reference has no value in this run
     */
    static HttpRequest build(HttpCheckParams params, SecretsProvider secrets, HttpCheckVariables variables) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(variables.resolve(params.url())))
                .timeout(JdkHttpCheckExchange.REQUEST_TIMEOUT)
                .method(params.method(), HttpRequest.BodyPublishers.noBody());
        params.headers().forEach((name, value) -> builder.header(name, variables.resolve(value)));
        HttpCheckParams.Auth auth = params.auth();
        if (auth != null) {
            String secret = secrets.find(auth.credential())
                    .orElseThrow(() -> new HttpCheckCredentialException(auth.credential()));
            builder.header(auth.header(), auth.headerValue(secret));
        }
        return builder.build();
    }
}
