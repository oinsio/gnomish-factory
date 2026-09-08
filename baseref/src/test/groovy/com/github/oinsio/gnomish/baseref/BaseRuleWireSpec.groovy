package com.github.oinsio.gnomish.baseref

import spock.lang.Specification

/**
 * {@link BaseRule}'s wire vocabulary round-trip (FR7 of add-base-ref-resolution): every constant,
 * {@link BaseRule#UNKNOWN} included, survives a {@code wireValue()}/{@code fromWire} round trip —
 * the "every wire vocabulary has a round-trip spec" rule of `.claude/rules/testing.md`, data-driven
 * over {@link BaseRule#values()} rather than a hand-listed subset so a new constant fails this spec
 * until it is covered.
 */
class BaseRuleWireSpec extends Specification {

    // FR7: every rule constant — including UNKNOWN's own wire value, once written — reads back as
    // itself.
    def "every BaseRule constant round-trips through its wire value: #rule"() {
        expect:
        BaseRule.fromWire(rule.wireValue()) == rule

        where:
        rule << BaseRule.values()
    }

    // FR7: a token no constant claims folds to UNKNOWN rather than failing — the actual
    // forward-compat behavior, distinct from UNKNOWN round-tripping its own token above.
    def "an unrecognized wire token folds to UNKNOWN"() {
        expect:
        BaseRule.fromWire('some-future-rule-token') == BaseRule.UNKNOWN
    }
}
