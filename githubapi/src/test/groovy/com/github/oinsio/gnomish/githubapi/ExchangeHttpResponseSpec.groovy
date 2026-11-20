package com.github.oinsio.gnomish.githubapi

import com.github.oinsio.gnomish.httpegress.HttpCheckExchange
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import spock.lang.Specification

/**
 * FR15 of migrate-verify-to-executor-contract, design D16 ("Current signatures the decision
 * changes"): the guarded exchange's response read through the JDK's {@code HttpResponse} shape the
 * core's cache, rate-limit classification and retry predicate keep taking.
 */
class ExchangeHttpResponseSpec extends Specification {

    def "the adapted response carries the request, its URI, the status, the headers and the body"() {
        given:
        def request = HttpRequest.newBuilder(URI.create('http://127.0.0.1:1/repos/a/b')).GET().build()
        def headers = HttpHeaders.of([ETag: ['"v1"']], { a, b -> true })
        def response = new ExchangeHttpResponse(request, new HttpCheckExchange.Response(304, headers, 'body'))

        expect:
        response.request().is(request)
        response.uri() == request.uri()
        response.statusCode() == 304
        response.headers().firstValue('ETag').get() == '"v1"'
        response.body() == 'body'
        response.version() == HttpClient.Version.HTTP_1_1
        response.previousResponse().isEmpty()
        response.sslSession().isEmpty()
    }
}
