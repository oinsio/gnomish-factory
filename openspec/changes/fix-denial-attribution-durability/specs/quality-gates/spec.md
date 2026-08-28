# quality-gates — delta

## ADDED Requirements

### Requirement: Delegating decorator completeness gate
A named ArchUnit rule SHALL fail the build when a production class implements an interface and holds a same-type delegate — a field, constructor parameter, or record component of the interface type, including `Supplier` of it — without overriding every `default` method that interface declares (superinterfaces included). Leaf implementers (no same-type delegate) are out of scope: for a leaf the constant default is a truthful "I don't have this"; only for a delegator is it a silent replacement of the delegate's real behavior. Justified exemptions — self-delegating overloads whose inherited body is the decorator's documented intent — SHALL live in a named allowlist beside the rule, one entry per class and method with the reason. The rule's own spec SHALL seed a violating class and assert the rule fails it.
<!-- implements FR9 of fix-denial-attribution-durability -->

#### Scenario: An unforwarded default fails the build
- **WHEN** a production class holds a delegate of the interface it implements and leaves one of that interface's default methods unoverridden, and it is not in the allowlist
- **THEN** the architecture rule fails the build naming the class and the method

#### Scenario: A leaf implementer is untouched
- **WHEN** a production class implements an interface with defaults but holds no same-type delegate
- **THEN** the rule does not constrain it

#### Scenario: An allowlisted self-delegating overload passes with its reason recorded
- **WHEN** a delegating class inherits a default whose body delegates to an abstract method the class does override, and the class is allowlisted with a reason
- **THEN** the rule passes and the allowlist entry names the class, the method, and the reason
