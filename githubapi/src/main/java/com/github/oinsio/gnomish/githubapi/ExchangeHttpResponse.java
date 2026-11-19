package com.github.oinsio.gnomish.githubapi;

import com.github.oinsio.gnomish.httpegress.HttpCheckExchange;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import javax.net.ssl.SSLSession;

/**
 * The guarded exchange's response as the JDK's {@link HttpResponse} shape (design D16 of
 * migrate-verify-to-executor-contract, "Current signatures the decision changes"): {@link
 * GithubHttpClient#send} keeps returning {@code HttpResponse<String>}, so the conditional-request
 * cache, the rate-limit classification and the retry predicate read status, headers and body exactly
 * as they did over the client's own {@code HttpClient}. It carries no previous response and no TLS
 * session: the guarded exchange follows redirects itself, one judged hop at a time.
 *
 * <p>Implements FR15 of migrate-verify-to-executor-contract.
 */
final class ExchangeHttpResponse implements HttpResponse<String> {

    private final HttpRequest request;
    private final HttpCheckExchange.Response response;

    ExchangeHttpResponse(HttpRequest request, HttpCheckExchange.Response response) {
        this.request = request;
        this.response = response;
    }

    @Override
    public int statusCode() {
        return response.status();
    }

    @Override
    public HttpRequest request() {
        return request;
    }

    @Override
    public Optional<HttpResponse<String>> previousResponse() {
        return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
        return response.headers();
    }

    @Override
    public String body() {
        return response.body();
    }

    @Override
    public Optional<SSLSession> sslSession() {
        return Optional.empty();
    }

    @Override
    public URI uri() {
        return request.uri();
    }

    @Override
    public HttpClient.Version version() {
        return HttpClient.Version.HTTP_1_1;
    }
}
