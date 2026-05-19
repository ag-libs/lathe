# Java Completion Engine Design: Eclipse JDT & NetBeans
## A Reference for Building an LSP Test Suite

---

## 1. Primary Source References

| Source | URL | What it covers |
|---|---|---|
| JDT Core Programmer Guide / Completion | https://wiki.eclipse.org/JDT_Core_Programmer_Guide/Completion | Deep internal design of CompletionEngine, CompletionParser, assistNode mechanics |
| JDT API: `CompletionProposal` | https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/CompletionProposal.html | All proposal kinds the engine can emit |
| JDT API: `CompletionRequestor` | https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/CompletionRequestor.html | Callback protocol between engine and UI |
| JDT API: `ICodeAssist` / `codeComplete` | https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/guide/jdt_api_codeassist.htm | Public API entry point, visibility options, relevance model |
| JDT LS `CompletionHandlerTest` | https://github.com/eclipse-jdtls/eclipse.jdt.ls (path: `org.eclipse.jdt.ls.tests/.../handlers/CompletionHandlerTest.java`) | Concrete LSP-level test cases |
| JDT LS CHANGELOG | https://github.com/eclipse-jdtls/eclipse.jdt.ls/blob/main/CHANGELOG.md | Feature history / edge cases discovered over time |
| NetBeans Editor Completion API | https://bits.netbeans.org/dev/javadoc/org-netbeans-modules-editor-completion/architecture-summary.html | Threading model, provider registration, query type taxonomy |
| NetBeans `CompletionProvider` SPI | https://bits.netbeans.org/dev/javadoc/org-netbeans-modules-editor-completion/org/netbeans/spi/editor/completion/CompletionProvider.html | createTask contract, autoQueryTypes |
| NetBeans `CompletionItem` SPI | https://bits.netbeans.org/dev/javadoc/org-netbeans-modules-editor-completion/org/netbeans/spi/editor/completion/CompletionItem.html | Insert-prefix semantics per proposal type |
| NetBeans Architecture Q&A | https://bits.netbeans.org/dev/javadoc/org-netbeans-modules-editor-completion/architecture-summary.html | Gesture logging events, threading contract |
| JDT index performance issue | https://github.com/eclipse-jdtls/eclipse.jdt.ls/issues/1846 | DiskIndex caching strategy, "complete on type name" path |
| NetBeans java.completion CI label | https://github.com/apache/netbeans (CI label `java.completion`) | Module identity, test scope boundary |

---

## 2. Eclipse JDT Completion Engine — Design Choices

### 2.1 Overall Pipeline

The JDT completion pipeline has three stages:

**Stage 1 — Parse with recovery (`CompletionParser` + `CompletionScanner`, ~6 KLOC + ~1 KLOC)**

The parser is a modified ECJ (Eclipse Compiler for Java). When the scanner reaches the cursor position it normally inserts a synthetic EOF token so the parser sees a well-formed prefix. The parser overrides many `consume*` methods to, instead of building a normal AST node, build one of the `CompletionOn*` family (e.g. `CompletionOnMemberAccess`, `CompletionOnSingleNameReference`, `CompletionOnQualifiedTypeReference`). This node is stored in the field `assistNode`.

Because code is always syntactically broken at the cursor, the parser relies heavily on error-recovery machinery (`RecoveredElement currentElement`): it speculatively builds a partial structure and tries to splice `assistNode` into a plausible parent via `attachOrphanCompletionNode()`.

Three important auxiliary fields guide the semantic engine:
- `assistNode` — the node directly under the cursor
- `assistNodeParent` — immediately enclosing node (drives expected-type inference)
- `enclosingNode` — the outer `if (x instanceof Foo)` guard for casted-receiver proposals

The parser also maintains three co-indexed stacks (`elementKindStack`, `elementInfoStack`, `elementObjectInfoStack`) to record what tokens were seen without needing to interpret the automaton-generated parser states.

**Stage 2 — Resolve and throw (`LookupEnvironment` + `CompletionEngine`, >13 KLOC)**

After parsing, `CompletionEngine` calls `lookupEnvironment.completeTypeBindings(compilationUnit, true)`. When the resolver reaches `assistNode` it throws `CompletionNodeFound` (a sentinel exception) containing binding context. `CompletionEngine.complete(ASTNode, ...)` catches this and dispatches into one of the many `completionOn*` methods, which call various `find*` methods (e.g. `findFields`, `findMethods`, `findTypes`).

**Stage 3 — Rank and emit**

For each candidate, an `InternalCompletionProposal` is created with:
- `#completion` — the string to insert
- source range (start, end, token start)
- `#relevance` — a positive integer; proposals with higher relevance appear first
- Proposal kind constant (see §2.3)
- Additional metadata: declaring type signature, method signature, parameter names, modifiers, javadoc

These are fed to `CompletionRequestor.accept(CompletionProposal)` one at a time.

### 2.2 Key Design Choices

**Partial AST, not full reparse.** The engine explicitly restricts the source range to `[method.bodyStart, cursorLocation]` by default, treating everything after the cursor as EOF. This keeps the parse fast even for huge files. Lambda introduction (bug 423987) forced an extension mechanism because lambdas need to see their closing `}`.

**Heuristics over correctness.** The engine documentation explicitly says "opportunistic": it invents plausible structure when the parser cannot produce a correct AST. This is fundamentally different from normal compilation and why the code is so large.

**Casted-receiver proposals.** When the code contains `if (o instanceof Foo) { o.| }`, the engine invents a narrowed type for `o` and proposes `Foo`'s members. This is not derivable from static type alone.

**Relevance ranking.** Relevance is computed by adding points for:
- Expected type match (expression context matches proposal type)
- Exact case match (prefix matches exactly)
- Camel-case / subword match (secondary)
- Unqualified vs qualified name
- Accessibility (public > package > protected > private)
- Whether a constructor is used in `new` expression context

**Visibility filtering.** Option `CODEASSIST_VISIBILITY_CHECK` enables filtering of inaccessible elements (private members of superclasses, package-private from other packages). When enabled, JDT does not emit proposals the user cannot legally reference.

**Import auto-add.** For type completions, JDT marks the proposal with `FIELD_IMPORT` / `TYPE_IMPORT` / `METHOD_IMPORT` flag and includes the additional text edit to add the import. The LSP layer converts this to LSP `additionalTextEdits`.

**Two-phase LSP: completion + resolve.** JDT-LS returns lightweight `CompletionItem`s at `textDocument/completion` time, deferring heavy computations (Javadoc, full text edits, snippet expansions) to `completionItem/resolve`. This is controlled by a client capability flag.

**Index-backed type search.** The "complete on type name" path queries a `DiskIndex` (one file per JAR/classpath entry). A known bottleneck is that this index avoids in-memory caching to save heap, which can be slow for large JDK indexes (issue #1846).

### 2.3 All Proposal Kinds (CompletionProposal constants)

These are the concrete kinds that JDT can produce — every test case maps to one or more:

| Kind | Description |
|---|---|
| `ANNOTATION_ATTRIBUTE_REF` | Attribute name inside an annotation (`@Foo(attr|)`) |
| `ANONYMOUS_CLASS_DECLARATION` | Anonymous class body for `new Interface() {|` |
| `ANONYMOUS_CLASS_CONSTRUCTOR_INVOCATION` | Constructor call for anonymous class |
| `CONSTRUCTOR_INVOCATION` | `new Foo(|` — constructor |
| `FIELD_IMPORT` | Static field import for static-import completions |
| `FIELD_REF` | Instance/static field access (`obj.fi|`) |
| `FIELD_REF_WITH_CASTED_RECEIVER` | Field on narrowed (instanceof-guarded) receiver |
| `JAVADOC_BLOCK_TAG` | `@param`, `@return` etc. inside Javadoc |
| `JAVADOC_FIELD_REF` | `{@link Type#field}` in Javadoc |
| `JAVADOC_INLINE_TAG` | `{@code}`, `{@link}` etc. |
| `JAVADOC_METHOD_REF` | `{@link Type#method(params)}` in Javadoc |
| `JAVADOC_PARAM_REF` | `@param name` — completing parameter names |
| `JAVADOC_TYPE_REF` | Type reference in Javadoc |
| `JAVADOC_VALUE_REF` | `{@value}` reference |
| `KEYWORD` | Java reserved word (`pub|`, `ret|`) |
| `LABEL_REF` | `break label|` / `continue label|` |
| `LOCAL_VARIABLE_REF` | Local variable or parameter (`loc|`) |
| `METHOD_DECLARATION` | Method override skeleton generation |
| `METHOD_IMPORT` | Static method import |
| `METHOD_NAME_REFERENCE` | Method reference `Type::method|` |
| `METHOD_REF` | Regular method call (`obj.met|`) |
| `METHOD_REF_WITH_CASTED_RECEIVER` | Method on narrowed receiver |
| `MODULE_DECLARATION` | `module-info.java` module name |
| `MODULE_REF` | Module reference in `requires`, `exports` |
| `PACKAGE_REF` | Package name (`java.ut|`) |
| `POTENTIAL_METHOD_DECLARATION` | Method stub when type is unknown |
| `TYPE_REF` | Type name (class/interface/enum/record/annotation) |
| `VARIABLE_DECLARATION` | Variable name suggestion from type |

---

## 3. NetBeans Completion Engine — Design Choices

### 3.1 Overall Architecture

NetBeans uses a **provider registry** pattern. Providers are registered per MIME type via the XML layer:

```
Editors/<mime-type>/CompletionProviders/<ProviderClass>
```

The infrastructure is in `org-netbeans-modules-editor-completion`. When a document with a registered MIME type loads, all providers are instantiated.

### 3.2 Key Interfaces

**`CompletionProvider`** (SPI)
- `createTask(int queryType, JTextComponent)` — called on AWT thread; must return a `CompletionTask` quickly (reschedule heavy work)
- `getAutoQueryTypes(JTextComponent, String typedText)` — bitmask controlling when popup appears automatically
- Query types: `COMPLETION_QUERY_TYPE`, `DOCUMENTATION_QUERY_TYPE`, `TOOLTIP_QUERY_TYPE`, `ALL_COMPLETION_QUERY_TYPE`

**`CompletionTask`** (SPI)
- `query(CompletionResultSet, Document, caretOffset)` — compute proposals; can run off-AWT via `AsyncCompletionTask`
- `refresh(CompletionResultSet)` — called when user types more characters while popup is open (filter/narrow existing results without a full re-query)
- `cancel()` — infrastructure can cancel an in-progress query

**`CompletionItem`** (SPI)
- `getInsertPrefix()` — the text used for narrowing as user continues typing; for methods, only the method name (not parameters)
- `defaultAction(JTextComponent)` — what happens on Enter
- `processKeyEvent(KeyEvent)` — allows items to claim non-Enter key presses (e.g. `.` to commit and immediately start member access)
- `createToolTipTask()` — returns a `CompletionTask` for the tooltip panel
- `createDocumentationTask()` — returns a `CompletionTask` for the documentation panel

### 3.3 Key Design Choices

**Threading model.** All `CompletionProvider` methods are initially called on the AWT thread. `AsyncCompletionTask` / `AsyncCompletionQuery` abstracts the rescheduling to a background thread. The `preQueryUpdate()` hook runs on AWT before the background query to snapshot any mutable UI state.

**Multiple independent providers.** Unlike JDT's monolithic engine, NetBeans allows multiple `CompletionProvider` instances to contribute to a single popup simultaneously. Their results are merged. This enables the java.completion module, code templates, and third-party providers to coexist.

**Three result panes.** The completion infrastructure explicitly models three separate result surfaces: the completion list, the documentation panel (Javadoc), and the tooltip (method parameter help). Each has its own `CompletionTask` lifecycle.

**Refresh vs. re-query.** When the user continues typing, `CompletionTask.refresh(resultSet)` is called rather than `query()`. This lets providers do a cheap client-side filter on already-computed results. If `refresh` is passed `null`, the provider must do a full re-query.

**`getAutoQueryTypes` bitmask.** The provider controls whether the popup appears automatically after any keystroke (auto-popup), only on explicit Ctrl+Space, or not at all. The java.completion module uses a delay (default 250 ms) and triggers auto-popup on `.` and `@`.

**Gesture logging.** The infrastructure sends named events to a logger for UX telemetry: `COMPL_INVOCATION` (explicit/implicit), `COMPL_KEY_SELECT`, `COMPL_KEY_SELECT_DEFAULT` (Enter), `COMPL_MOUSE_SELECT`. These can guide test assertions about selection mechanics.

---

## 4. Concrete User Behaviour → Use Cases → Test Cases

This section maps user behaviours (what the user does in the editor) to proposal kinds and specific aspects to assert in your LSP test suite.

### 4.1 Keyword Completion

| User behaviour | Trigger text example | Expected proposals | Notes |
|---|---|---|---|
| Types start of access modifier | `pub` in class body | `public`, `public static`, `public final` | JDT: `KEYWORD` kind |
| Types `ret` in method body | `ret` | `return` | |
| Types `th` in method body | `th` | `throw`, `throws`, `this` | Context-sensitive: `throws` only in method signature |
| Types `sw` in method body | `sw` | `switch` | |
| Types `inst` in expression | `inst` | `instanceof` | |
| Types `rec` in class context | `rec` | `record` | Java 16+ |
| Types `sea` in class | `sea` | `sealed` | Java 17+ |
| Types `per` after `sealed class Foo ` | `per` | `permits` | Positional keyword, only after sealed decl |

**Assertions:** proposal text, proposal kind = KEYWORD, no additional text edits needed.

### 4.2 Type Name Completion

| User behaviour | Trigger text example | Expected proposals | Notes |
|---|---|---|---|
| Starts typing unimported type | `ArrayL` | `ArrayList` (+ auto-import) | TYPE_REF + additional import edit |
| Types FQN prefix | `java.util.Arr` | `ArrayList` with full label | PACKAGE_REF chain then TYPE_REF |
| Types in `extends` clause | `class Foo extends Abs` | Abstract types + interfaces | Filtering: only non-final types |
| Types in `implements` clause | `class Foo implements Ser` | `Serializable`, `Service`… | Only interfaces |
| Types in `throws` clause | `void f() throws IOE` | `IOException` | Only Throwable subtypes |
| Types in annotation position | `@Ove` | `@Override`, `@Override` | TYPE_REF for annotation types only |
| Types in `catch` block | `catch (IOE` | `IOException` | Only Throwable subtypes |
| Types in generics | `List<St` | `String`, `Stack`… | TYPE_REF inside type argument |
| Types a record component type | `record Foo(St` | `String`, `Stack`… | Java 16+ context |

**Assertions:** `additionalTextEdits` contains import insertion when type is not in scope; no import edit when already imported or in same package; FQN variant present if import would be ambiguous.

### 4.3 Member Access Completion (Field & Method)

| User behaviour | Trigger text example | Expected proposals | Notes |
|---|---|---|---|
| Types `.` after expression | `str.` | All public instance members of `String` | METHOD_REF, FIELD_REF |
| Types `.` after `this` | `this.` | All accessible members of current type | Includes private fields |
| Types partial name after `.` | `str.sub` | `substring(int)`, `substring(int, int)` | Prefix filter |
| Types `.` after static type | `Math.` | Static members only | No instance members of `Class<Math>` |
| Types after superclass method | `super.` | Inherited methods | Includes methods from parent up chain |
| Types after cast | `((String) obj).` | String methods | JDT: METHOD_REF_WITH_CASTED_RECEIVER |
| Types after instanceof guard | `if (o instanceof String s) { o.` | String methods proposed for `o` | Pattern matching narrowing (Java 16+) |
| Types inside chained call | `list.stream().fi` | `filter`, `findFirst`, `findAny` | Receiver is `Stream<T>` |

**Assertions:** inherited members from `Object` present (e.g. `toString()`, `hashCode()`); private members not present when accessing from outside the declaring class; overloads listed as separate items with distinct parameter lists.

### 4.4 Constructor Completion

| User behaviour | Trigger text example | Expected proposals | Notes |
|---|---|---|---|
| Types `new ` | `new ArrayL` | `ArrayList()`, `ArrayList(int)`, `ArrayList(Collection<?>)` | CONSTRUCTOR_INVOCATION |
| Types `new` for abstract type | `new AbstractL` | Propose with anonymous class skeleton | ANONYMOUS_CLASS_DECLARATION |
| Types `new` for interface | `new Runna` | `Runnable` + anonymous class / lambda option | ANONYMOUS_CLASS_DECLARATION |
| Types in diamond context | `Map<String, Integer> m = new Hash` | `HashMap<>()` with inferred diamond | TYPE_REF + smart generic inference |

**Assertions:** each constructor overload is a separate proposal; relevance is higher for no-arg constructor in assignment context; diamond operator `<>` is inserted correctly.

### 4.5 Variable Name Suggestion

| User behaviour | Trigger text example | Expected proposals | Notes |
|---|---|---|---|
| Types variable name after type | `ArrayList list` → cursor on `list` | `list`, `arrayList`, `strings` | VARIABLE_DECLARATION / local var name |
| Types parameter name | `void foo(String ` | `string`, `str`, `s` | Based on type name heuristics |
| Prefix-based filtering | `ArrayList my` | `myList`, `myArrayList` | Prefix preserved |

### 4.6 Annotation Completion

| User behaviour | Trigger text example | Expected proposals | Notes |
|---|---|---|---|
| Types `@` in class body | `@Ove` | `@Override`, `@Deprecated`, `@SuppressWarnings` | TYPE_REF for annotation types |
| Types inside `@SuppressWarnings(` | `@SuppressWarnings("un` | `"unchecked"`, `"unused"` | ANNOTATION_ATTRIBUTE_REF value strings |
| Types attribute name | `@MyAnnotation(val` | `value`, `name` (declared attrs) | ANNOTATION_ATTRIBUTE_REF |
| Multi-attribute annotation | `@MyAnnotation(a=1, b` | remaining attribute names | Already-supplied attrs filtered out |

### 4.7 Import Statement Completion

| User behaviour | Trigger text example | Expected proposals | Notes |
|---|---|---|---|
| Types `import java.` | `import java.u` | `java.util`, `java.util.*` | PACKAGE_REF |
| Types `import static` | `import static java.lang.Math.` | Static members of Math | FIELD_IMPORT, METHOD_IMPORT |
| Types after wildcard present | `import java.util.*` then `import java.io.F` | `File`, `FileWriter`… | Should not duplicate already-wildcard-imported |

### 4.8 Lambda & Method Reference Completion

| User behaviour | Trigger text example | Expected proposals | Notes |
|---|---|---|---|
| Types in functional interface context | `Runnable r = () -> Sys` | `System.out.println` | Completion inside lambda body |
| Types method reference | `list.forEach(System.out::pri` | `println` | METHOD_NAME_REFERENCE |
| Types constructor reference | `Supplier<Foo> s = Foo::` | `new` | CONSTRUCTOR_INVOCATION via method ref |
| Lambda with target type inference | `list.stream().map(s -> s.to` | String methods | Receiver type inferred from `List<String>` |

**Note from JDT-LS history:** lambda completion fails randomly if the parser's source range extension is not applied correctly (CHANGELOG: `testCompletion_Lambda fails randomly`). Test that completion works inside lambda bodies, including nested lambdas.

### 4.9 Javadoc Completion

| User behaviour | Trigger text example | Expected proposals | Notes |
|---|---|---|---|
| Types `@` in Javadoc comment | `/** @pa` | `@param`, `@return`, `@throws` | JAVADOC_BLOCK_TAG |
| Types `{@` | `{@li` | `{@link}`, `{@linkplain}`, `{@code}` | JAVADOC_INLINE_TAG |
| Types `@param ` in method Javadoc | `@param ` | Parameter names of the method | JAVADOC_PARAM_REF |
| Types `@throws ` | `@throws IOE` | Exception types in `throws` clause | JAVADOC_TYPE_REF |
| Types inside `{@link }` | `{@link String#sub` | `substring` overloads | JAVADOC_METHOD_REF |

### 4.10 Snippet / Code Generation Completion

| User behaviour | Trigger text example | Expected proposals | Notes |
|---|---|---|---|
| Types `sysout` | `sysout` | Expands to `System.out.println(|)` | Snippet; JDT-LS uses `completionItem/resolve` to set the snippet body |
| Types `psvm` | `psvm` | Expands to `public static void main(String[] args) {|}` | |
| Types `fori` | `fori` | `for (int i = 0; i < |; i++) {}` | |
| Types in class body | (empty line) | `Override inherited methods`, `Generate constructors`, `Generate getters/setters` | NetBeans generates whole METHOD_DECLARATION stubs |

**Assertion:** the `insertText` or `textEdit` in the resolved `CompletionItem` contains `$0`, `$1`… placeholders (LSP snippet format); `insertTextFormat` = `Snippet`.

### 4.11 Completion in Broken / Incomplete Code

| User behaviour | Scenario | Expected behaviour |
|---|---|---|
| Unclosed method body | `void foo() { Str` (no `}`) | Must still produce TYPE_REF proposals |
| Missing semicolon | `int x = 5\nStr` | Must still produce TYPE_REF |
| Unclosed generic | `List<` | TYPE_REF for type arguments |
| Mid-expression completion | `return new ArrayList<>(Col` | TYPE_REF / CONSTRUCTOR_INVOCATION |
| Completion inside `if` condition | `if (str.` | String methods |
| Completion inside `for` init | `for (int i = arr.` | int-returning methods of arr's type |
| Missing import | `Foo f = new Fo` where `Foo` not imported | TYPE_REF + import edit still returned |

**This is the most important category for your LSP** — the CompletionParser exists entirely to handle these cases. Verify that your parser's error-recovery feeds the completion engine rather than short-circuiting to empty results.

### 4.12 Visibility & Scope

| User behaviour | Scenario | Expected behaviour |
|---|---|---|
| Accessing private field | `other.privateField` from outside class | NOT proposed (visibility check on) |
| Accessing package-private from different package | `other.pkgField` | NOT proposed |
| `this.` in static context | `static void foo() { this.` | No proposals (no `this` in static) |
| Shadowed variable | Local `x` shadows field `x`; `this.x` | Field `x` proposed via `this.` |
| Variable not yet declared | `void foo() { x.` before `String x = ...` | No proposals for `x` (or error diagnostic) |

### 4.13 Module System (JPMS) Completion (Java 9+)

| User behaviour | Trigger text in `module-info.java` | Expected proposals |
|---|---|---|
| `requires ` | `requires java.` | Accessible module names | MODULE_REF |
| `exports ` | `exports com.` | Own packages | PACKAGE_REF |
| `opens ` | `opens com.` | Own packages | PACKAGE_REF |
| `provides ` | `provides java.util.s` | `ServiceLoader`-compatible types | TYPE_REF |
| `provides X with ` | `provides Svc with com.` | Concrete implementations of `Svc` | TYPE_REF |

### 4.14 Record-Specific Completion (Java 16+)

| User behaviour | Scenario | Expected behaviour |
|---|---|---|
| Completing in record component list | `record Point(int x, do` | `double` keyword / TYPE_REF |
| Completing accessor | `Point p = ...; p.` | Component accessors `x()`, `y()` proposed |
| Completing `compact constructor` | Inside `Point { }` | Access to component names as local vars |

### 4.15 Sealed Class Completion (Java 17+)

| User behaviour | Scenario | Expected behaviour |
|---|---|---|
| `permits` clause | `sealed class Shape permits ` | TYPE_REF to subtypes in same compilation unit |
| `switch` on sealed | `switch(shape) { case ` | Permitted subtype names for patterns |

---

## 5. Design Decisions to Copy / Avoid

### From JDT — recommended patterns

- **Separate completionOn* AST node family** from normal AST — lets you add completions without touching the main parser grammar, and makes debugging tractable.
- **`assistNodeParent` for expected-type inference** — knowing what the parent expression expects lets you rank proposals that match the expected type higher.
- **Relevance integer** rather than boolean — allows multi-factor ranking without a fixed priority scheme.
- **Proposal kind enum** — decouples the computation of what to propose from the rendering / application of the proposal; essential for LSP's lazy resolve pattern.
- **Cancellation check** inside search loops — critical for responsiveness; JDT-LS checks `IProgressMonitor.isCanceled()` before each index entry.

### From JDT — known pain points

- **Single-file monolith** (`CompletionEngine` > 13 KLOC) — hard to maintain.
- **`DiskIndex` cache miss** on large JARs — if you own your index, add an LRU memory cache layer on top.
- **Lambda source-range extension** — if your parser restricts the range to the cursor and the cursor is inside a lambda, the closing `}` for the lambda is beyond the range; you need to extend the range when you detect an unclosed lambda.
- **Two-phase `completion` / `resolve`** — clients that don't implement `completionItem/resolve` will miss snippet bodies and full text edits; negotiate this capability explicitly in `initialize`.

### From NetBeans — recommended patterns

- **`refresh()` for incremental filtering** — calling the full query on every keypress is expensive; the refresh path lets you filter an already-computed result set cheaply.
- **Three separate task surfaces** (list / tooltip / documentation) — model them independently so the user can open documentation without re-triggering the full query.
- **`getAutoQueryTypes` per keystroke** — returning non-zero for `.` triggers auto-popup on member access, which is the most common trigger. Returning 0 for other characters avoids useless popups.
- **Multi-provider composition** — register separate providers for snippets, live templates, and semantic completions; they compose automatically.

---

## 6. Suggested Test Suite Structure

```
completion/
  keyword/
    test_keyword_public.java
    test_keyword_return.java
    test_keyword_sealed_permits.java
    ...
  type_ref/
    test_unimported_type_adds_import.java
    test_extends_filters_non_final.java
    test_implements_interfaces_only.java
    test_generic_type_argument.java
    ...
  member_access/
    test_field_ref_instance.java
    test_method_ref_overloads.java
    test_casted_receiver.java
    test_instanceof_narrowing.java
    test_static_members_only.java
    ...
  constructor/
    test_new_overloads.java
    test_anonymous_class.java
    test_diamond_inferred.java
    ...
  annotation/
    test_annotation_type_ref.java
    test_attribute_name.java
    test_attribute_value_string.java
    ...
  lambda/
    test_body_completion.java
    test_method_reference.java
    test_constructor_reference.java
    test_nested_lambda.java
    ...
  javadoc/
    test_block_tag.java
    test_param_names.java
    test_link_type_ref.java
    ...
  snippet/
    test_sysout_snippet.java
    test_method_override_skeleton.java
    ...
  broken_code/         ← most important
    test_unclosed_brace.java
    test_missing_semicolon.java
    test_unclosed_generic.java
    test_mid_expression.java
    ...
  visibility/
    test_private_not_proposed.java
    test_no_this_in_static.java
    ...
  module_info/
    test_requires_module.java
    test_exports_package.java
    test_provides_with.java
    ...
  records/
    test_component_accessor.java
    ...
  sealed/
    test_permits_subtypes.java
    test_switch_pattern.java
    ...
```

Each test fixture should capture:
1. Source file content with a `|` marker indicating cursor position
2. The LSP `textDocument/completion` request position
3. Expected proposal labels present in the response
4. Expected proposal labels absent (negative assertions)
5. For type refs: presence/absence of `additionalTextEdits` (import)
6. For snippets: `insertTextFormat = Snippet` and `$0` placeholder in resolved item
7. For method refs: overload count and parameter label correctness (via `textDocument/signatureHelp`)
