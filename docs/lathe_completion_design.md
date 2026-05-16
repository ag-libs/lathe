# Lathe Java Completion Design

## Goals

- NetBeans-quality semantic completion
- Robust on broken/incomplete source
- Stable under continuous typing
- Low-latency (<200 ms target)
- Maintainable across future Java language features

---

# Core Principles

## 1. Completion operates on current source, not previous trees

Completion must always use the current editor snapshot.

Avoid:
- stale-tree heuristics
- edit-history-dependent logic
- "last clean AST" as primary mechanism

Use:
- immutable source snapshot
- fresh completion compile

---

## 2. Sentinel injection is the primary completion mechanism

When completion is requested:

```java
foo.
```

Inject:

```java
foo.__LATHE_SENTINEL__
```

Compile the modified source and locate:

```java
IdentifierTree("__LATHE_SENTINEL__")
```

Its parent is:

```java
MemberSelectTree
```

Receiver expression:

```java
foo
```

becomes fully attributed by javac.

---

# Completion Pipeline

```text
Completion Request
    │
    ▼
Take immutable source snapshot
    │
    ▼
Inject sentinel
    │
    ▼
Fresh javac compile
    │
    ├─► receiver attributed?
    │         │
    │         ├─► YES → enumerate members → return completion items
    │         │
    │         └─► NO
    ▼
Optional repair round
    │
    ▼
Recompile
    │
    ├─► receiver attributed?
    │         │
    │         ├─► YES → return completion items
    │         │
    │         └─► NO → fallback/empty
```

---

# Sentinel Design

## Sentinel token

```text
__LATHE_SENTINEL__
```

Properties:
- ASCII-only
- invalid Java identifier in normal codebases
- easy AST lookup
- no positional remapping needed

Lookup strategy:
- search AST by identifier name
- avoid offset tracking after repairs

---

# Receiver Attribution Rule

Completion succeeds when:

```java
receiver.type != null
&& receiver.type.getKind() != TypeKind.ERROR
```

Do NOT wait for:
- clean compilation
- zero diagnostics
- successful flow analysis

Only receiver attribution matters.

---

# Repair Strategy

## Repair philosophy

Repair is:
- bounded
- opportunistic
- latency-constrained

Completion is best-effort.

Do NOT attempt to fully repair arbitrary broken Java.

---

# Repair Rounds

## Round 0

Always attempt:

```text
sentinel injection only
```

No speculative repairs before compiler input.

---

## Round 1

If receiver attribution fails:

- inspect parse diagnostics
- apply minimal safe repairs
- recompile once

---

# Compiler-Guided Repair

Use javac diagnostics as syntax-repair hints.

Examples:

```text
')' expected
';' expected
'}' expected
reached end of file while parsing
```

Apply insertions:
- right-to-left
- by descending position
- local to cursor region

Diagnostics should be treated as javac implementation details, not stable API contracts.

Do not parse localized human-readable messages when avoidable.
Prefer:
- diagnostic positions
- diagnostic categories
- known javac parse-error patterns

---

# Safe Repair Tokens

Safe automatic insertions:

```text
)
]
}
;
```

Possibly:

```text
:
```

for ternary repair.

Avoid arbitrary speculative rewrites.

---

# Cases That Usually Work Without Repair

```java
foo.
foo.bar().
this.
super.
String.
arr[0].
new Foo().
((Foo)obj).
```

---

# Cases That Often Need Repair

## Unclosed method calls

```java
foo(bar.
```

Needs:

```java
foo(bar.__SENTINEL__);
```

---

## Unclosed blocks

```java
if (x) {
    foo.
```

Needs:

```java
if (x) {
    foo.__SENTINEL__;
}
```

---

## Missing semicolon

```java
int x = 1
foo.
```

Needs:

```java
int x = 1;
foo.__SENTINEL__;
```

---

## Ternary expressions

```java
cond ? foo.
```

Needs:

```java
cond ? foo.__SENTINEL__ : null;
```

---

# Hard Cases

## 1. Lambda target typing

```java
foo(x -> x.)
```

May fail even after syntax repair because:
- overload resolution incomplete
- target type unresolved

Compiler diagnostics alone may not solve this.

---

## 2. Incomplete generics

```java
obj.<String,
```

Context-sensitive parsing:
- generics
- comparisons
- shift operators

Hard to recover reliably.

---

## 3. Broken earlier code poisoning parse state

```java
int x =
foo.
```

Earlier syntax errors may corrupt nearby parse structure.

Repairs must remain local.

---

# Latency Budget

## Hard budget

Target:

```text
<200 ms
```

Completion is latency-sensitive.

---

# Recommended Timing Model

```text
0–100 ms:
    sentinel compile

100–180 ms:
    optional repair compile

>200 ms:
    fallback/return partial
```

---

# Timeout Policy

Use:
- absolute request deadline
- cancellation
- bounded repair rounds

Example:

```java
long deadline = now + budget;
```

All phases check remaining time.

---

# Repair Limits

## Automatic popup completion

Allow:
- 1 normal compile
- 1 repair compile

Maximum.

---

## Explicit completion (Ctrl-Space)

Allow:
- slightly larger budget
- additional repair attempts if necessary

Still keep repair bounded.

---

# Javac Configuration

Recommended:

```text
-proc:none
```

Avoid:
- annotation processing
- unnecessary compilation stages
- bytecode generation work

Do not rely on internal javac options initially.

Internal javac flags and unsupported compiler internals should only be introduced if profiling proves they are necessary.

---

# File Manager Design

## One file manager per module

Recommended:

```text
ModuleCompileContext
    fileManager
```

Use sequentially:

```text
one active JavacTask at a time
```

Do not share one file manager concurrently across multiple compilation tasks.

---

# Future Optimization Possibilities

Only introduce additional complexity if profiling demonstrates a real bottleneck.

Possible future optimizations:
- file manager pools
- parallel completion variants
- aggressive source reduction
- compiler-stage limiting
- specialized repair heuristics

Do not implement these prematurely.

---

# Performance Measurement

Performance must be measured continuously with comprehensive logging.

Log at minimum:
- total completion latency
- parse time
- attribution time
- repair-trigger frequency
- repair success rate
- timeout frequency
- cancellation frequency
- queue wait time
- completion success rate
- p50/p95/p99 latency

Track separately:
- automatic popup completion
- explicit completion requests

Optimization decisions should be driven by measured latency and failure distributions, not assumptions.

---

# Fallback Hierarchy

## Tier 1

Current source + sentinel

---

## Tier 2

One syntax repair round

---

## Tier 3

Previous clean AST fallback

---

## Tier 4

Lexical completion only

---

# Key Architectural Insight

Treat javac as:

```text
a best-effort semantic engine
operating under a strict latency SLA
```

Do not attempt to:
- fully repair arbitrary Java
- build a custom parser
- perfectly emulate IDE compiler internals

Favor:
- predictable latency
- bounded recovery
- graceful degradation
- semantic correctness when possible
