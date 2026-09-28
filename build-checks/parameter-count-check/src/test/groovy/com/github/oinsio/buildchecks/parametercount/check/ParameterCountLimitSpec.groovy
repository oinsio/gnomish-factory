package com.github.oinsio.buildchecks.parametercount.check

import com.google.errorprone.CompilationTestHelper
import spock.lang.Specification

/**
 * The semantics of the parameter-count gate, compiled against the check on Error Prone's own
 * harness (NFR-R1 of add-parameter-count-gate, design D10). A {@code // BUG: Diagnostic contains:}
 * line marks the declaration below it as the one expected to fail; the harness fails the feature
 * both on a missing diagnostic and on an unexpected one, so every passing case is asserted too.
 * The wiring into a real module is the functional suite's job, not this spec's.
 */
class ParameterCountLimitSpec extends Specification {

    private static final String EXEMPTION = 'import com.github.oinsio.buildchecks.parametercount.ParameterLimitExemption;'

    def helper = CompilationTestHelper.newInstance(ParameterCountLimit, getClass())

    /** {@code int p1, ..., int pN}. */
    private static String params(int count) {
        (1..count).collect { "int p${it}" }.join(', ')
    }

    def 'FR1: a method with eight parameters fails, naming the count, the limit and the transformation'() {
        expect:
        helper.addSourceLines('Test.java',
                'class Test {',
                '  // BUG: Diagnostic contains: 8 parameters; the limit is 7. Group the values that travel together into a parameter object',
                "  void m(${params(8)}) {}",
                '}').doTest()
    }

    def 'FR1: a constructor with eight parameters fails'() {
        expect:
        helper.addSourceLines('Test.java',
                'class Test {',
                '  // BUG: Diagnostic contains: 8 parameters; the limit is 7',
                "  Test(${params(8)}) {}",
                '}').doTest()
    }

    def 'FR1: seven parameters pass'() {
        expect:
        helper.addSourceLines('Test.java',
                'class Test {',
                "  Test(${params(7)}) {}",
                "  void m(${params(7)}) {}",
                '}').doTest()
    }

    def 'FR2: a record constructor passes — canonical, compact and explicit'() {
        expect:
        helper.addSourceLines('Canonical.java',
                "record Canonical(${params(8)}) {}").addSourceLines('Compact.java',
                "record Compact(${params(8)}) {",
                '  Compact {}',
                '}').addSourceLines('Explicit.java',
                "record Explicit(${params(9)}) {",
                "  Explicit(${params(8)}) { this(p1, p2, p3, p4, p5, p6, p7, p8, 0); }",
                '}').doTest()
    }

    def 'FR2: an ordinary method housed in a record does not inherit its exemption'() {
        expect:
        helper.addSourceLines('Holder.java',
                'record Holder(int value) {',
                '  // BUG: Diagnostic contains: 8 parameters',
                "  void m(${params(8)}) {}",
                '}').doTest()
    }

    def 'FR3: an overriding or implementing method passes; the declaration it follows is counted'() {
        expect:
        helper.addSourceLines('Shape.java',
                'interface Shape {',
                '  // BUG: Diagnostic contains: 8 parameters',
                "  void m(${params(8)});",
                '}').addSourceLines('Impl.java',
                'class Impl implements Shape {',
                "  @Override public void m(${params(8)}) {}",
                '}').addSourceLines('Sub.java',
                'class Sub extends Impl {',
                "  @Override public void m(${params(8)}) {}",
                '}').doTest()
    }

    def 'a compiler-generated constructor is not counted'() {
        // The anonymous class gets an eight-parameter constructor javac writes, not the author.
        // Error Prone never visits it; this pins that the check needs no branch of its own for it.
        expect:
        helper.addSourceLines('Base.java',
                EXEMPTION,
                'class Base {',
                '  @ParameterLimitExemption(reason = "fixture: the supertype of an anonymous class")',
                "  Base(${params(8)}) {}",
                '  static Base make() { return new Base(1, 2, 3, 4, 5, 6, 7, 8) {}; }',
                '}').doTest()
    }

    def 'FR4: an annotated site with a reason passes'() {
        expect:
        helper.addSourceLines('Test.java',
                EXEMPTION,
                'class Test {',
                '  @ParameterLimitExemption(reason = "mirrors an externally fixed signature")',
                "  void m(${params(8)}) {}",
                '}').doTest()
    }

    def 'FR4: an annotated site with a blank reason fails'() {
        expect:
        helper.addSourceLines('Test.java',
                EXEMPTION,
                'class Test {',
                "  @ParameterLimitExemption(reason = \"${reason}\")",
                '  // BUG: Diagnostic contains: @ParameterLimitExemption needs a non-blank reason',
                "  void m(${params(8)}) {}",
                '}').doTest()

        where:
        reason << ['', '   ']
    }

    def 'FR4: an exemption on a declaration the gate would pass anyway fails, so the annotations stay the list of real exceptions'() {
        expect:
        helper.addSourceLines('Shape.java',
                'interface Shape {',
                '  // BUG: Diagnostic contains: 8 parameters',
                "  void m(${params(8)});",
                '}').addSourceLines('Test.java',
                EXEMPTION,
                'class Test implements Shape {',
                '  @ParameterLimitExemption(reason = "the signature was shrunk since")',
                '  // BUG: Diagnostic contains: @ParameterLimitExemption is unnecessary: this declaration passes the 7-parameter limit without it',
                "  void small(${params(7)}) {}",
                '  @ParameterLimitExemption(reason = "an override does not choose its signature")',
                '  // BUG: Diagnostic contains: @ParameterLimitExemption is unnecessary',
                "  @Override public void m(${params(8)}) {}",
                '}').addSourceLines('Rec.java',
                EXEMPTION,
                "record Rec(${params(8)}) {",
                '  @ParameterLimitExemption(reason = "a record is its own parameter object")',
                '  // BUG: Diagnostic contains: @ParameterLimitExemption is unnecessary',
                "  Rec(${params(8)}) { this.p1 = p1; this.p2 = p2; this.p3 = p3; this.p4 = p4;"
                        + ' this.p5 = p5; this.p6 = p6; this.p7 = p7; this.p8 = p8; }',
                '}').doTest()
    }

    def 'FR4: the exemption covers its own declaration only, not the classes declared inside it'() {
        expect:
        helper.addSourceLines('Test.java',
                EXEMPTION,
                'class Test {',
                '  @ParameterLimitExemption(reason = "the exempted declaration")',
                "  Object m(${params(8)}) {",
                '    return new Object() {',
                '      // BUG: Diagnostic contains: 8 parameters',
                "      void inner(${params(8)}) {}",
                '    };',
                '  }',
                '}').doTest()
    }

    def 'FR4: @SuppressWarnings on the enclosing class does not suppress'() {
        expect:
        helper.addSourceLines('Test.java',
                '@SuppressWarnings({"ParameterCountLimit", "all"})',
                'class Test {',
                '  // BUG: Diagnostic contains: 8 parameters',
                "  void m(${params(8)}) {}",
                '}').doTest()
    }
}
