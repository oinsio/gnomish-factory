package com.github.oinsio.gnomish.httpegress

import java.net.http.HttpRequest

/**
 * A scripted delegate exchange for the guard's specs: answers from a queue (the last answer
 * repeating) and records every request it was actually handed, so a spec sees which hops the guard
 * sent and how each was built.
 */
class Hops implements HttpCheckExchange {

    List<HttpCheckExchange.Response> answers
    List<HttpRequest> seen = []

    Hops(HttpCheckExchange.Response... answers) {
        this.answers = answers as List
    }

    @Override
    HttpCheckExchange.Response send(HttpRequest request) {
        seen << request
        answers[Math.min(seen.size() - 1, answers.size() - 1)]
    }
}
