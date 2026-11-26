package com.github.oinsio.gnomish.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaConstructorCall
import com.tngtech.archunit.core.domain.JavaMethodCall
import java.util.function.Predicate

/**
 * The typed call predicates and reach queries of the round's owner gates ({@code ExecutorPollOwnerSpec},
 * {@code RoundBracketOwnerSpec}; task 6.8 of extract-agent-round-runner, design D8): a call is judged
 * by the type of its target in the bytecode, never by its spelling, so another type's method of the
 * same name is not a hit.
 */
final class OwnerCalls {

    private OwnerCalls() {
    }

    /**
     * A call of {@code name} on {@code owner} or one of its implementations; with {@code parameters},
     * only the overload of those raw parameter types (an implementation's private overload is not it).
     */
    static DescribedPredicate<JavaMethodCall> calling(String owner, String name, List<String> parameters = null) {
        DescribedPredicate.describe("a call of ${name} on ${owner}".toString(), { JavaMethodCall call ->
            call.target.owner.isAssignableTo(owner) && call.target.name == name &&
            (parameters == null || call.target.rawParameterTypes*.name == parameters)
        } as Predicate<JavaMethodCall>)
    }

    static DescribedPredicate<JavaConstructorCall> building(String owner) {
        DescribedPredicate.describe("a construction of ${owner}".toString(), { JavaConstructorCall call ->
            call.target.owner.name == owner
        } as Predicate<JavaConstructorCall>)
    }

    static DescribedPredicate<JavaClass> notA(String type) {
        DescribedPredicate.describe("not an implementation of ${type}".toString(), { JavaClass javaClass ->
            !javaClass.isAssignableTo(type)
        } as Predicate<JavaClass>)
    }

    static DescribedPredicate<JavaClass> outside(List<String> names) {
        DescribedPredicate.describe("a class other than ${names}".toString(), { JavaClass javaClass ->
            !(javaClass.name in names)
        } as Predicate<JavaClass>)
    }

    /** The names of the methods of {@code owner} that make {@code call}. */
    static Set<String> callers(JavaClasses classes, String owner, DescribedPredicate<JavaMethodCall> call) {
        classes.get(owner).methodCallsFromSelf.findAll {
            call.test(it)
        }*.origin*.name as Set
    }

    /** The implementations of {@code port} that make {@code call}: the ones that hand it on. */
    static Set<String> implementationsCalling(JavaClasses classes, String port, DescribedPredicate<JavaMethodCall> call) {
        classes.findAll { JavaClass c ->
            c.isAssignableTo(port) && c.methodCallsFromSelf.any {
                call.test(it)
            }
        }*.name as Set
    }
}
