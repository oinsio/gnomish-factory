package com.github.oinsio.gnomish.githubapi

import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.net.ssl.SSLSession

/**
 * A response of a given status and headers, nothing else, for the specs of the rate-limit judgement
 * and the retry rule: both read only the status and the headers.
 */
final class RateLimitResponses {

    private RateLimitResponses() {}

    static HttpResponse<String> of(int status, Map<String, List<String>> headers) {
        new Answered(status, HttpHeaders.of(headers, { k, v -> true }))
    }

    private static final class Answered implements HttpResponse<String> {
        private final int status
        private final HttpHeaders headers

        Answered(int status, HttpHeaders headers) {
            this.status = status
            this.headers = headers
        }

        int statusCode() {
            status
        }

        HttpRequest request() {
            HttpRequest.newBuilder(URI.create('https://api.github.com/')).build()
        }

        Optional<HttpResponse<String>> previousResponse() {
            Optional.empty()
        }

        HttpHeaders headers() {
            headers
        }

        String body() {
            ''
        }

        Optional<SSLSession> sslSession() {
            Optional.empty()
        }

        URI uri() {
            URI.create('https://api.github.com/')
        }

        HttpClient.Version version() {
            HttpClient.Version.HTTP_1_1
        }
    }
}
