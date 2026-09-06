package com.github.oinsio.gnomish.architecture

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * The delegating-decorator rule itself (FR9, M5, design D7 of fix-denial-attribution-durability),
 * held apart from the gate that runs it: for each class, each interface it implements that it also
 * holds a delegate of, every default method of that interface must be declared by the class (or a
 * superclass) unless an exemption names that exact signature.
 *
 * <p>{@link DelegatingDecoratorCompletenessSpec} owns the production allowlist, the seeded shapes
 * and the assertions; this class owns only the reading of a class graph, so the rule can be
 * exercised over seeded exemption lists rather than over the production one alone.
 */
final class DelegatingDecoratorRule {

    private DelegatingDecoratorRule() {
    }

    /**
     * Findings for the given classes — one sorted line per unforwarded default, each naming the
     * signature rather than the bare method name, so two same-named defaults are told apart.
     */
    static List<String> unforwardedDefaults(Collection<Class<?>> classes, List<Map<String, String>> exemptions) {
        classes.collectMany { Class<?> type ->
            allInterfaces(type).collectMany { Class<?> iface ->
                if (!holdsDelegateOf(type, iface)) {
                    return []
                }
                iface.methods
                        .findAll {
                            it.isDefault() && !declares(type, it) && !exempt(type, it, exemptions)
                        }
                        .collect {
                            "${type.name} does not forward ${iface.simpleName}.${signature(it)}" as String
                        }
            }
        }
        .sort()
    }

    /**
     * An exemption covers exactly one signature. Matching a bare method name would hand the
     * exemption to every same-named default the interface later gains — silently widening an
     * allowlist whose whole promise is that it is per method.
     */
    static boolean exempt(Class<?> type, Method method, List<Map<String, String>> exemptions) {
        exemptions.any {
            it.type == type.name && it.method == method.name && it.params == params(method)
        }
    }

    /** {@code name(Simple,Types)} — the one form the allowlist and the findings both speak. */
    static String signature(Method method) {
        "${method.name}(${params(method)})" as String
    }

    /** The comma-joined simple names of a method's parameters; empty for a no-argument method. */
    static String params(Method method) {
        method.parameterTypes*.simpleName.join(',')
    }

    /**
     * The interfaces the rule judges: the project's own seams. The Groovy runtime's {@code
     * GroovyObject} is excluded — every Groovy class implements it, its defaults are the
     * metaclass plumbing the compiler generates, and no author ever "forgot" to forward them.
     */
    static Set<Class<?>> allInterfaces(Class<?> type) {
        Set<Class<?>> found = [] as Set<Class<?>>
        for (Class<?> c = type; c != null && c != Object; c = c.superclass) {
            c.interfaces.each { collectInterfaces(it, found) }
        }
        found.findAll {
            !it.name.startsWith('groovy.') && !it.name.startsWith('org.codehaus.groovy.')
        } as Set<Class<?>>
    }

    private static void collectInterfaces(Class<?> iface, Set<Class<?>> found) {
        if (found.add(iface)) {
            iface.interfaces.each { collectInterfaces(it, found) }
        }
    }

    /** A field, record component, or constructor parameter that is — or carries — the interface. */
    private static boolean holdsDelegateOf(Class<?> type, Class<?> iface) {
        List<List<Object>> held = (type.declaredFields.findAll {
            !Modifier.isStatic(it.modifiers)
        }
        .collect { [it.type, it.genericType] } as List<List<Object>>) +
        (type.declaredConstructors.collectMany { ctor ->
            [
                ctor.parameterTypes.toList(),
                ctor.genericParameterTypes.toList()
            ].transpose()
        } as List<List<Object>>)
        held.any { raw, generic ->
            isDelegate(raw as Class<?>, generic as Type, iface)
        }
    }

    /**
     * Held directly, or named as a type argument of whatever holds it — {@code Supplier<I>} (the
     * {@code LeasedEnvironment} shape), {@code List<I>} (a fan-out, which drops the unforwarded
     * capability for every element), {@code Optional<I>}, and any container added later. The
     * enumeration is deliberately absent: a consumer-shaped false positive ({@code Function<I, X>})
     * is answered by one allowlist line, which is far cheaper than a decorator the gate never saw.
     */
    private static boolean isDelegate(Class<?> raw, Type generic, Class<?> iface) {
        if (iface.isAssignableFrom(raw)) {
            return true
        }
        generic instanceof ParameterizedType &&
                (generic as ParameterizedType).actualTypeArguments.any {
                    it instanceof Class && iface.isAssignableFrom(it as Class)
                }
    }

    private static boolean declares(Class<?> type, Method method) {
        for (Class<?> c = type; c != null && c != Object; c = c.superclass) {
            if (c.declaredMethods.any {
                        it.name == method.name && it.parameterTypes == method.parameterTypes
                    }) {
                return true
            }
        }
        false
    }
}
