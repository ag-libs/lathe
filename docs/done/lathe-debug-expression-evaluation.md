# Lathe — Debugger Expression Evaluation (javac front-end + JDI interpreter)

Design for the debugger's `IEvaluationProvider`: evaluating a Java expression in the context of a
suspended stack frame, over the same `.lathe/` bytecode Lathe already debugs.

This refines [lathe-debug-support.md](lathe-debug-support.md) §8 (provider context), §13 Phase 4
(advanced), and resolves its **open decision #3** (evaluation strategy) in favour of a **javac
front-end + JDI tree-interpreter**, delivered **basic-first and grown on demand**.

**Status: implemented.**
Expression evaluation has shipped: reads, method/constructor invocation, `String` concat,
`instanceof`, explicit `this`/`super`, force-loading cold classes, and object-scoped evaluation for
collection/map logical views, used by conditional breakpoints, watches/hover, and the debug console.
The deferred tail is the write path — gap DB-1 (assignment / `setVariable`) — and DB-2
(array-creation expressions). The seam, the two-stage architecture, and the phase boundaries below
remain the authoritative reference.

---

## 1. Scope

**In:** evaluate an arbitrary Java expression against a live, suspended frame — reading locals,
`this`, and fields; operators; casts; `instanceof`; array/index; and (from v2) method invocation —
returning a `com.sun.jdi.Value` the DAP adapter renders. Powers the DAP `evaluate` request (debug
console, watches, hover) and `evaluateForBreakpoint` (conditional breakpoints, logpoints).

**Out (documented limitations, §15):** constructs that require emitting and loading *new* bytecode
into the debuggee — lambdas, anonymous/local classes, switch expressions with synthetic bodies.
These are rejected with a clear message in the interpreter era and are the natural boundary for a
future compile-and-inject fallback (§18).

**Growth philosophy:** ship the smallest evaluator that unblocks a real feature (conditional
breakpoints + simple watches), then add capability slice by slice only when a use case demands it.
Every slice is independently reviewable and leaves the evaluator correct for what it claims to
support.

---

## 2. Relationship to the debug design

- `IEvaluationProvider` is the last provider still fully deferred in
  [lathe-debug-support.md](lathe-debug-support.md) §8; this document is its detailed design.
- Evaluation runs *inside a suspended frame*, so it is **gated on Phase 1** of the debug plan
  (attach + `IVirtualMachineManagerProvider` + `ISourceLookUpProvider` + `IStackFrameManager`).
  Today `LatheProviderContext` registers only the no-op HCR provider (Phase 0), so the prerequisites
  are not yet in place.
- `evaluateForBreakpoint` is what actually powers **conditional breakpoints and logpoints**, which
  the debug plan lists under Phase 2. This creates a coupling the debug plan did not call out (§8):
  a minimal read-only evaluator is a Phase-2 prerequisite, not a Phase-4 luxury.

---

## 3. Principles and invariants

Evaluation inherits the debug invariants and adds three of its own.

- **javac is the only source of Java semantics.** Scope, overload resolution, implicit `this`,
  inherited/static members, generics, and constant folding all come from attributing the expression
  with Lathe's javac — never from text scanning or a hand-rolled resolver (CLAUDE.md rule). The
  interpreter consumes an already-attributed tree; it never re-derives meaning.
- **The interpreter never loads code into the debuggee.** It reads state (`getValue`) and, from v2,
  invokes existing methods (`invokeMethod`); it never defines a class in the target VM. This is the
  bright line that separates the interpreter from compile-and-inject (§18) and keeps the frequent
  path (conditional breakpoints) cheap and side-effect-free.
- **Pure reads run no debuggee code.** Reading a local, a field, or `this`, and all arithmetic /
  comparison, execute entirely in `lathe-server` against JDI mirrors. Debuggee code runs *only* for
  explicit method invocation (v2+). A read-only condition therefore never resumes a thread.
- **Fail-soft.** An unresolved symbol, an unsupported construct, or a debuggee exception completes
  the evaluation future exceptionally with a clear message; it never crashes the session, corrupts
  state, or leaves a thread wedged.

---

## 4. The seam — `IEvaluationProvider`

The embedded `com.microsoft.java.debug.core:0.53.1` defines the contract entirely in JDI terms —
input is an expression string plus a JDI context, output is a `com.sun.jdi.Value`:

```java
public interface IEvaluationProvider extends IProvider {
  CompletableFuture<Value> evaluate(String expr, ThreadReference thread, int frameDepth);        // console / watch / hover
  CompletableFuture<Value> evaluate(String expr, ObjectReference thisContext, ThreadReference t); // object-scoped
  CompletableFuture<Value> evaluateForBreakpoint(IEvaluatableBreakpoint bp, ThreadReference t);   // conditional bkpts + logpoints
  CompletableFuture<Value> invokeMethod(ObjectReference obj, String name, String sig,
                                        Value[] args, ThreadReference t, boolean invokeSuper);    // toString rendering + explicit calls
  boolean isInEvaluation(ThreadReference thread);
  void clearState(ThreadReference thread);
}
```

Two consequences fix the design:

- **The adapter marshals the result.** Lathe returns a JDI `Value`; the adapter's variable formatter
  renders it and, for objects, calls back into `invokeMethod` for `toString`. Lathe's job ends at
  producing the `Value`.
- **`invokeMethod` is needed from v1**, even before the interpreter itself invokes methods, because
  the variable formatter uses it to render object results. So JDI method invocation (§6) is in scope
  from the first slice; class *loading* never is.

---

## 5. Architecture — two stages

Evaluation is a bridge between two worlds that neither side can span alone: javac knows *what the
expression means* but has no live objects; JDI has the live objects but no type/scope knowledge.

```
expr string ──▶ [Stage 1: javac, in-frame] ──▶ attributed AST (resolved Symbols/Types)
                                                       │
                                       [bridge: binary name + JVM descriptor]
                                                       ▼
suspended frame (JDI) ──▶ [Stage 2: tree-interpreter over JDI] ──▶ com.sun.jdi.Value
```

### 5.1 Stage 1 — javac makes the expression meaningful, *in-frame*

The expression must be attributed in the exact scope of the frame, not in isolation, so that `x`
resolves to the right local/field/inherited member and overloads and generics are settled.

**Splice-and-attribute** (reuses Lathe's existing attributed-analysis pipeline):

1. Take the source of the frame's enclosing method (located via the Phase-1 source map, run
   backwards from the JDI `Location`).
2. Splice the user expression at the breakpoint line as `var __lathe = (EXPR);`.
3. Attribute the whole unit with Lathe's `SourceAnalysisSession` (including the sentinel/recovery
   machinery used for incomplete editor text).
4. Lift the attributed subtree for `EXPR`.

Because it is attributed *in situ*, every node carries a resolved `Symbol`/`Type`, and private
members of the enclosing class are legally in scope — which is exactly why a stand-alone wrapper
class would not compile.

### 5.2 The bridge — compile-time symbols → runtime handles

Each resolved node needs a runtime counterpart in the live VM. The universal keys are the **binary
name** and the **JVM descriptor**, both of which javac produces and JDI indexes by. Reuse Lathe's
existing descriptor logic (as in `ReferenceTarget` / `RunnableScanner`) rather than re-deriving it.

| javac (Stage 1)            | JDI handle (Stage 2)      | Crossing                                                                 |
|----------------------------|---------------------------|--------------------------------------------------------------------------|
| local `VarSymbol`          | `LocalVariable`           | match by name in `frame.visibleVariables()` → `frame.getValue(...)`      |
| field `VarSymbol`          | `Field`                   | declaring `ReferenceType` by binary name → `objectRef`/`refType.getValue`|
| `MethodSymbol`             | `Method`                  | erased descriptor → `refType.methodsByName(name)` filtered by `signature`|
| `Type` (cast/`instanceof`) | `ReferenceType`           | look up by binary name in the VM                                         |

### 5.3 Stage 2 — interpret the attributed tree over JDI

Walk the attributed tree bottom-up, producing a `Value` per node:

- **Literals** → `vm.mirrorOf(...)`.
- **Local / field / `this` reads** → the `getValue` calls above (no debuggee code).
- **Operators** (`+ - * / % << < == != && || !` …) → unwrap the JDI primitive mirror, compute
  host-side applying javac's already-decided numeric promotion and boxing/unboxing, mirror the
  result back. `String +` is concatenation (via `String.valueOf` / `StringBuilder` invocations,
  v2+); `==` vs `.equals` follow Java semantics; `&&`/`||` short-circuit.
- **Casts / `instanceof` / array access / index** → JDI type checks + `ArrayReference.getValue`.
- **Method calls (v2+)** → recurse to evaluate args to `Value[]`, then `objectRef.invokeMethod(...)`
  (or `classType.invokeMethod` for static), resolving the JDI `Method` via the descriptor bridge.

The interpreter uses the live thread *only* for method invocation; everything else is pure JDI reads
and host-side arithmetic.

---

## 6. Execution and threading discipline

Method invocation is where JDI is unforgiving; these rules are mandatory from v1 (because the
formatter's `toString` uses `invokeMethod`) and v2 (interpreter calls):

- Invocations run on the **suspended thread**, which JDI briefly resumes *only that thread*
  (`ObjectReference.INVOKE_SINGLE_THREADED`) — otherwise a monitor held elsewhere deadlocks the call.
- `isInEvaluation(thread)` must report `true` for the duration of an invocation so the adapter
  **suppresses breakpoint/step events** fired *inside* the call (e.g. a breakpoint in the `toString`
  being rendered). `clearState(thread)` tears down per-thread caches afterward.
- A debuggee exception surfaces as `InvocationException`; unwrap it into an evaluation error message.
- Guard returned object refs from GC while in use (`disableCollection` / `enableCollection`).
- Handle `IncompatibleThreadStateException` (thread not suspended at an event) as a clean refusal.

---

## 7. Result marshaling

Return the `com.sun.jdi.Value`. The adapter's variable formatter renders primitives directly and, for
object references, calls `IEvaluationProvider.invokeMethod(obj, "toString", "()Ljava/lang/String;",
…)` — i.e. back into Lathe. Keep the `invokeMethod` implementation consistent with the interpreter's
own invocation path so console output and variable rendering agree.

---

## 8. Conditional breakpoints and logpoints

`evaluateForBreakpoint` receives an `IEvaluatableBreakpoint` (the condition and/or log message) and a
thread; it must return a `BooleanValue` for a condition, or evaluate the interpolated fragments of a
logpoint message.

- Parse-and-attribute the condition **once** and cache it per breakpoint (keyed off the
  `IEvaluatableBreakpoint`); conditions are hit on every pass of the line and must not recompile each
  time.
- Read-only conditions (`i == 5`, `node.next == null`) are the common case and, by §3, run **no**
  debuggee code — the cheapest, safest path, and the reason a read-only v1 is enough to ship
  conditional breakpoints.

**Phasing consequence:** the debug plan's Phase 2 (conditional breakpoints, logpoints) depends on at
least the read-only evaluator. This document therefore recommends delivering **v1 as part of Phase
2**, with method invocation and beyond following in Phase 4 (§13).

---

## 9. Feature scope — v1 and growth

The interpreter grows one capability at a time; each version is a shippable, correct subset.

| Version | Adds | Unblocks | Runs debuggee code? |
|---|---|---|---|
| **v1** | literals; local/`this`/field reads; arithmetic, relational, logical, bitwise ops; casts; `instanceof`; array access | conditional breakpoints, logpoints, simple watches | no |
| **v2** | method + constructor invocation; `String` concatenation; explicit boxing/unboxing via calls | watches/console with calls, `toString` rendering parity | yes (existing methods only) |
| **v3** | assignment and compound assignment; ternary; `setVariable` support | mutating watches, quick fixes at a breakpoint | yes |
| **deferred** | lambdas, anonymous/local classes, switch expressions needing synthetic bodies | — | would require emitting new bytecode |

**The generated-class boundary.** Anything that needs a *new* class to exist in the debuggee falls
outside the interpreter by construction. v1–v3 reject such expressions with a specific message
(`"lambda expressions are not supported by the interpreter"`). If demand appears, the escape hatch is
a compile-and-inject fallback for exactly those nodes (§18) — a deliberate, separately-scoped
extension, not creeping scope in the interpreter.

---

## 10. Prerequisites and assumptions

- **Phase 1 of the debug plan** (attach + VM-manager + source-lookup + stack-frame providers). The
  source map is reused, run backwards, to find the frame's source for Stage 1.
- **`.lathe/` bytecode carries a `LocalVariableTable`** (`javac -g` / at least `-g:vars`). Without it
  locals cannot be resolved by name — the same prerequisite line-number tables impose on Phase 1
  breakpoints. Verify the capture compiles with debug info; if not, that is a capture-side change,
  not an evaluator one.
- **The frame's source is available** to Lathe (module source roots / reactor `.lathe/<dep-rel>`), as
  for source lookup.

---

## 11. Server surface and wiring

- **`LatheEvaluationProvider implements IEvaluationProvider`**, registered in `LatheProviderContext`
  alongside the Phase-1 providers.
- **Context assembly:** `(thread, depth)` → JDI `StackFrame` → `location()` (declaring type, method,
  line) + `visibleVariables()` + `thisObject()`; map the location to a source via the Phase-1 lookup.
- **Capabilities:** the DAP `initialize` response must advertise `supportsEvaluateForHovers`,
  `supportsConditionalBreakpoints`, `supportsLogPoints`, and (at v3) `supportsSetVariable`.
- **No new command surface.** Evaluation flows over the existing DAP socket (`evaluate`,
  breakpoints); it does not add an LSP command.

---

## 12. Neovim

Nothing new. `nvim-dap` already routes the debug console, watches, hover, conditional-breakpoint
conditions, and logpoints over DAP to the adapter. The only visible change is that these features
start working once the provider is registered and the capabilities are advertised.

---

## 13. Phased plan

Aligned to the debug plan's phases; commit prefixes follow the run/test/debug convention.

- **v1 — read-only interpreter (delivered within Phase 2).** Stage-1 splice-and-attribute; the
  symbol→JDI bridge; the read-only interpreter; `evaluate` (read-only), `evaluateForBreakpoint`,
  `invokeMethod` (for `toString` only), `isInEvaluation`/`clearState`; capabilities.
  *Commit:* `feat: read-only expression evaluation for conditional breakpoints`.
- **v2 — method invocation (Phase 4).** Interpreter method/constructor calls; `String` concat;
  boxing via calls; the full invocation discipline (§6).
  *Commit:* `feat: evaluate method-calling expressions in the debugger`.
- **v3 — assignment & setVariable (Phase 4).** Assignment/compound assignment, ternary,
  `setVariable`.
  *Commit:* `feat: mutating expression evaluation and setVariable`.
- **deferred — generated-class fallback.** Only if demanded: compile-and-inject for lambdas/anonymous
  classes (§18). Its own series.

---

## 14. Testing

- **Front end (no live VM):** unit-test splice-and-attribute — a curated set of expressions resolves
  to the expected symbols/types against a compiled fixture class (locals, `this`, inherited/static
  members, generics, overloads); unresolved names surface as errors.
- **Bridge (no live VM):** unit-test `Symbol` → binary-name/descriptor mapping for fields, methods
  (overloads, arrays, primitives, generics-erased), and types.
- **Interpreter (live VM, post-Phase-1):** an integration smoke that attaches to a suspended captured
  test and evaluates, at minimum: a local, a field via `this`, an arithmetic/relational expression, a
  boolean condition used as a breakpoint condition, and (v2) a method call whose result renders via
  `toString`. Reuse the debug host harness (`DapHostHandshakeTest`).
- **Refusals:** an unsupported construct (a lambda) and a debuggee-throwing call both produce clean,
  specific evaluation errors, not session failures.

---

## 15. Limitations

- **No generated-class constructs** (lambdas, anonymous/local classes, synthetic switch bodies) until
  a compile-and-inject fallback lands, if ever (§9, §18).
- **Method invocation has side effects** and can deadlock on ill-behaved code; invocations honour
  single-threaded resume but a pathological `toString`/getter can still stall — surfaced as a
  timeout/refusal, not a hang.
- **Requires `-g` bytecode** for local resolution (§10).
- **Gated on Phase 1** — no evaluation without a working attach and frame model.

---

## 16. Open decisions

1. **Invocation timeout policy** — fixed budget vs. configurable; how to present a stalled invocation.
2. **`evaluate` context differentiation** — whether `watch`/`repl`/`hover`/`clipboard` differ (error
   verbosity, side-effect permission) or are treated uniformly.
3. **Caching granularity** — cache attributed conditions per breakpoint (§8); whether to also cache
   general `evaluate` front-ends keyed by (expression, method, line).
4. **Numeric/semantic fidelity scope** — how far to chase exact JLS corner cases (char arithmetic,
   overflow, widening in compound assignment) before deferring to the compile-and-inject fallback.

---

## 17. Change inventory

| Module | Change |
|---|---|
| `lathe-server` | New `LatheEvaluationProvider` in the `debug` package; register it in `LatheProviderContext`; a Stage-1 front-end helper (reusing `SourceAnalysisSession`); a `Symbol`→JDI descriptor bridge (reusing existing descriptor logic); advertise the eval capabilities in `initialize`. |
| `lathe-core` | None expected beyond what Phase 1 already needs; descriptor helpers reused, not duplicated. |
| Neovim runtime | None (DAP already routes evaluate/watch/hover/conditions). |
| Docs | This document; cross-link from [lathe-debug-support.md](lathe-debug-support.md) §8/§13 and note the Phase-2 coupling (§8 here). |

The blast radius is confined to the `lathe-server` `debug` package plus the reuse of existing
analysis helpers.

---

## 18. Rejected alternatives (decision record)

Recorded from the design discussion so the choice is not relitigated.

- **JDT `ASTEvaluationEngine`** (what upstream java-debug's own provider uses) — full language
  support, but requires the Eclipse JDT Java project model, i.e. the jdtls dependency the whole debug
  design rejects (`lathe-debug-support.md` §4). **Rejected**: contradicts the architecture and the
  JPMS story.
- **Compile-and-inject** (compile the expression to a class, load it into the debuggee over JDI,
  invoke it) — semantically exact and complete because javac + the JVM do the work, and it is how JDT
  operates. **Not chosen now** because it concentrates complexity in a nontrivial runtime harness
  (marshal class bytes into the VM, `MethodHandles.Lookup.defineHiddenClass` in the debuggee,
  loader/module care, per-expression caching) and runs generated code in the target on every eval.
  **Retained as the future fallback** for exactly the generated-class constructs the interpreter
  cannot handle (§9) — a clean hybrid boundary rather than an all-or-nothing engine swap.
- **Reuse `jdk.jshell`** — an excellent *reference* for driving javac over synthetic snippets and for
  the compile-and-inject execution pattern (`ExecutionControl` SPI), but **not embeddable** as the
  engine: JShell evaluates in its *own* managed JVM with its *own* accreted variables, cannot bind to
  a suspended frame's live locals/`this`, and cannot hand back a `com.sun.jdi.Value` in the debuggee
  — the two requirements that define debugger evaluation. **Read for technique, not linked.**

**Chosen:** javac front-end + JDI tree-interpreter, basic-first (§9), because it fits the
no-jdtls / single-VM / attach-only architecture, keeps the frequent conditional-breakpoint path cheap
and side-effect-free, and starts small with a clear, bounded growth path.
