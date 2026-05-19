# Java LSP Completion Engine — High Level Design

## 1. Overview

This document describes the completion engine for an existing Java LSP server (`lathe`). The server already handles document synchronisation, diagnostics, attribution pipeline, and JAR indexing. This document covers only the addition of `textDocument/completion` support on top of that foundation.

Out of scope: threading orchestration, LSP wire protocol, build system integration, project import.

---

## 2. Constraints & Assumptions

```
Compiler          javax.tools + com.sun.source (Oracle javac only)
Parse cost        ~10ms per file
Attribution cost  ~150ms per file
Debounce          500ms idle before attribution fires
Client mode       Every-keystroke completion assumed (neovim/vscode aggressive)
Completion budget One sentinel parse per cache miss, zero on cache hit
Attribution       Never at completion time — background only
```

Existing infrastructure assumed present:

```
✓ OpenFile per URI with live content string
✓ Module-scoped attribution thread producing CompilationUnitTree
✓ JAR index queryable by simple-name prefix
✓ LSP didChange / didOpen / didClose handlers
```

---

## 3. State Model

### 3.1 OpenFile record (additions only)

```
OpenFile {
    // ── already present ──────────────────────────────────────────
    URI    uri
    String content          // live buffer, updated on every didChange
    int    version          // LSP document version

    // ── new: attributed snapshot ──────────────────────────────────
    TreeSnapshot snapshot   // volatile, written by attribution thread

    // ── new: sentinel cache ───────────────────────────────────────
    SentinelResult lastSentinel   // null if invalid
}
```

### 3.2 TreeSnapshot record

Immutable. Written atomically by attribution thread. Read by completion handling.

```
TreeSnapshot {
    CompilationUnitTree tree
    Trees               trees
    Elements            elements
    Types               types
    String              contentAtAttribution  // snapshot of content used
    int                 versionAtAttribution
    Instant             builtAt
    List<Diagnostic>    diagnostics
}
```

All five javac objects belong to the same javac context. Never mix objects from different snapshots.

### 3.3 SentinelResult record

Produced by one parse-only javac invocation on the injected buffer.

```
SentinelResult {
    // input
    int    docVersion         // version of content this was built from
    int    cursorOffset       // where sentinel was injected
    int    cursorLine         // expected line of sentinel

    // parse output
    String injectedContent    // full string that was parsed
    int    braceDepth         // how many braces were appended

    // extraction output
    SentinelContext context   // enum: see §5.4
    String prefix             // token being completed, from live buffer
    String receiverText       // non-null only for MemberAccess
    String enclosingClass     // text name, from ancestor walk
    String enclosingMethod    // text name, from ancestor walk
    int    argIndex           // -1 unless ArgumentPosition
    String declaredTypeText   // non-null only for VariableDeclaration

    // validity
    boolean valid             // false if sentinel landed at wrong line
}
```

### 3.4 State transitions

```
Event           LiveBuffer      TreeSnapshot        SentinelResult
─────────────────────────────────────────────────────────────────
didOpen         initialise      null                null
didChange       update content  unchanged           invalidate if
                update version  unchanged           structural char
                restart debounce
debounce fires  unchanged       replace atomically  set null
didClose        remove entry    release             release
```

---

## 4. Content Change Handling

```
on didChange(uri, changes, newVersion):

    f = files.get(uri)
    f.content = applyEdits(f.content, changes)
    f.version = newVersion

    char = lastCharacterOf(changes)

    if invalidatesSentinel(char):
        f.lastSentinel = null

    restartDebounce(uri, 500ms, () -> attributeInBackground(uri))

    // sentinel parse on change — separate thread, not shown here
    submitSentinelParse(uri, f.content, f.version, cursorFromChanges(changes))


invalidatesSentinel(char):
    return char in { '.', '(', ')', ' ', '{', '}', '@', '\n', ';' }
    // word characters do NOT invalidate — only prefix changes
```

---

## 5. Sentinel Injection

### 5.1 What a parse-only tree gives you

```
Available from parse-only tree          Not available (requires attribution)
────────────────────────────────        ────────────────────────────────────
Tree node kinds and structure           TypeMirror of any expression
Ancestor chain of any node              Scope at any position
Receiver expression as text             Element for any name
Declared type as text                   Overload resolution
Argument index in call                  Generic type substitution
Enclosing method/class names as text    Import resolution
Line/col of any node                    Visibility checking
```

### 5.2 Injection algorithm

```
inject(content, cursorOffset):

    // 1. find prefix token — scan left from cursor
    tokenStart = cursorOffset
    while tokenStart > 0 and isWordChar(content[tokenStart - 1]):
        tokenStart -= 1
    prefix = content[tokenStart .. cursorOffset]

    // 2. count unmatched open braces from file start to cursor
    depth = 0
    inString = false
    inLineComment = false
    for i in 0 .. cursorOffset:
        // skip string literals and comments
        if content[i] == '"' and not inLineComment: inString = !inString
        if content[i] == '/' and content[i+1] == '/': inLineComment = true
        if content[i] == '\n': inLineComment = false
        if inString or inLineComment: continue
        if content[i] == '{': depth += 1
        if content[i] == '}': depth -= 1

    // 3. assemble injected string
    injected = content[0 .. tokenStart]
             + "__SENTINEL__"
             + ";"
             + content[cursorOffset ..]     // keep tail: closing parens for lambdas
             + "\n}".repeat(max(0, depth))  // balance braces at end

    return injected, prefix, depth
```

### 5.3 Sentinel validation

```
validate(sentinelResult, expectedLine):

    path = findNodeByName(sentinelResult.tree, "__SENTINEL__")
    if path == null:
        return false

    actualLine = getLineNumber(sentinelResult.tree, path.leaf)
    if actualLine != expectedLine:
        return false   // javac recovery moved the node

    sentinelResult.sentinelPath = path
    return true
```

### 5.4 Context extraction

Walk the ancestor chain of the sentinel node upward. First matching rule wins.

```
extractContext(sentinelPath):

    parent = sentinelPath.parentPath.leaf

    match parent:

        MemberSelectTree where identifier == "__SENTINEL__":
            return MemberAccess {
                receiverText = parent.expression.toString()
            }

        VariableTree where name == "__SENTINEL__":
            return VariableDeclaration {
                declaredTypeText = parent.type.toString()
            }

        MethodInvocationTree where args contains sentinel:
            return ArgumentPosition {
                argIndex           = indexOf(sentinel, parent.args)
                enclosingReceiver  = parent.methodSelect.expression.toString()
                enclosingMethod    = parent.methodSelect.identifier.toString()
            }

        NewClassTree:
            return ConstructorCall {
                typeText = parent.identifier.toString()
            }

        AnnotationTree:
            return AnnotationContext {}

        LambdaExpressionTree:
            return LambdaBody {
                lambdaParam = findParamContainingSentinel(parent)
            }

        ExpressionStatementTree:
            return SimpleName {}   // default: name in statement position

    // walk further up for enclosing class and method names
    for node in sentinelPath:
        if node is MethodTree:  enclosingMethod = node.name
        if node is ClassTree:   enclosingClass  = node.simpleName
```

---

## 6. Completion Request Handling

### 6.1 Fast path — sentinel cache hit

```
conditions for cache hit:
    f.lastSentinel != null
    f.lastSentinel.docVersion == f.version
    f.lastSentinel.cursorLine == request.position.line
    lastCharTyped is word character

on cache hit:
    newPrefix = extractPrefix(f.content, request.position)
    return propose(f.lastSentinel, newPrefix, f.snapshot)
    // latency: <1ms, zero parses
```

### 6.2 Slow path — sentinel cache miss

```
on cache miss:
    injected, prefix, depth = inject(f.content, cursorOffset)

    // parse runs on separate thread — result awaited here
    sentinel = awaitSentinelParse(injected, cursorOffset, request.position.line)

    if sentinel == null or not sentinel.valid:
        return fallback(prefix, f.snapshot)    // §9

    sentinel.prefix = prefix
    f.lastSentinel  = sentinel

    return propose(sentinel, prefix, f.snapshot)
    // latency: ~10ms (parse) + <1ms (resolve + filter)
```

### 6.3 Cancellation

```
on $/cancelRequest(id):
    pending = pendingCompletions.get(id)
    if pending != null:
        pending.cancelled = true
        respond(id, ResponseError(-32800, "Request cancelled"))

on sentinel parse result delivered:
    if request.cancelled:
        discard result   // response already sent
        return
    deliver(result)
```

---

## 7. Type Resolution from Attributed Tree

All resolution uses `f.snapshot`. No parsing. No attribution.

### 7.1 Receiver type resolution

```
resolveReceiverType(receiverText, sentinel, snapshot):

    match receiverText:
        "this"  → return enclosingClassType(sentinel.enclosingClass, snapshot)
        "super" → return superclassType(sentinel.enclosingClass, snapshot)

        _ where isCapitalised(receiverText):
            // assume type name
            el = snapshot.elements.getTypeElement(receiverText)
            if el != null: return el.asType()

        _:
            // assume local variable or field
            type = scanForLocalDeclaration(
                       receiverText,
                       sentinel.enclosingClass,
                       sentinel.enclosingMethod,
                       sentinel.cursorLine,
                       snapshot)
            if type != null: return type

            // fallback: check fields of enclosing class
            return findFieldType(receiverText, sentinel.enclosingClass, snapshot)
```

### 7.2 Local variable scan

```
scanForLocalDeclaration(name, className, methodName, cursorLine, snapshot):

    classEl  = snapshot.elements.getTypeElement(className)
    methodEl = findMethod(classEl, methodName, snapshot)
    if methodEl == null: return null

    methodTree = snapshot.trees.getTree(methodEl)

    // TreeScanner on the attributed tree — Elements available
    return new TreeScanner<TypeMirror>() {
        visitVariable(node):
            if node.name == name:
                nodeLine = getLineNumber(snapshot.tree, node)
                if nodeLine < cursorLine:
                    el = snapshot.trees.getElement(
                             snapshot.trees.getPath(snapshot.tree, node))
                    return el.asType()
            return super.visitVariable(node)
    }.scan(methodTree.body)
```

### 7.3 Lambda parameter type resolution

```
resolveLambdaParamType(sentinel, snapshot):

    // sentinel.enclosingReceiver = "list"
    // sentinel.enclosingMethod   = "forEach"
    // sentinel.argIndex          = 0 (the lambda itself)
    // lambda param index         = 0 (first param of lambda)

    receiverType = resolveReceiverType(sentinel.enclosingReceiver, sentinel, snapshot)
    if receiverType == null: return null

    methodEl = resolveMethod(sentinel.enclosingMethod, receiverType,
                             sentinel.argIndex, snapshot)
    if methodEl == null: return null

    // get the functional interface type at the lambda's argument position
    paramType = methodEl.parameters[sentinel.argIndex].asType()
    // substitute receiver's type arguments
    paramType = snapshot.types.asMemberOf(receiverType, methodEl)

    // find SAM method of the functional interface
    samEl   = findSAM(paramType, snapshot)
    samType = snapshot.types.asMemberOf(paramType, samEl)

    // lambda param type is SAM's first parameter
    return samType.parameterTypes[sentinel.lambdaParamIndex]
```

---

## 8. Proposal Generation

### 8.1 Member access proposals

```
proposeMemberAccess(receiverType, prefix, isStaticAccess, snapshot):

    receiverEl = snapshot.types.asElement(receiverType)
    if receiverEl == null: return []

    return snapshot.elements.getAllMembers(receiverEl)
        |> filter: visibility(m, enclosingClass, snapshot)
        |> filter: m.isStatic == isStaticAccess
        |> filter: not isBridgeMethod(m)
        |> filter: not isSyntheticMethod(m)
        |> filter: m.simpleName.startsWith(prefix)
        |> sort:   relevance(m, expectedType, prefix, snapshot) descending
        |> map:    toCompletionItem(m, receiverType, snapshot)
```

### 8.2 Simple name proposals

Four sources, merged and deduped:

```
proposeSimpleName(sentinel, prefix, snapshot):

    proposals = []

    // 1. locals and parameters visible at cursor
    proposals += scanForLocalDeclarations(
                     sentinel.enclosingClass,
                     sentinel.enclosingMethod,
                     sentinel.cursorLine,
                     prefix, snapshot)

    // 2. members of enclosing class
    enclosingEl = snapshot.elements.getTypeElement(sentinel.enclosingClass)
    proposals += snapshot.elements.getAllMembers(enclosingEl)
                     |> filter: simpleName.startsWith(prefix)
                     |> filter: visibility(m, sentinel.enclosingClass, snapshot)

    // 3. type names from JAR index (already present in lathe)
    proposals += index.queryByPrefix(prefix)
                     |> map: toTypeRefItem(fqn, isImported(fqn, snapshot))

    // 4. keywords valid at statement level
    proposals += keywordsForContext(sentinel.context, prefix)

    return dedup(proposals)
```

### 8.3 Variable name suggestions

```
proposeVariableNames(declaredTypeText, prefix, snapshot):

    typeEl = snapshot.elements.getTypeElement(declaredTypeText)
    if typeEl == null: return []

    simpleName = typeEl.simpleName.toString()   // e.g. "ArrayList"

    candidates = [
        decapitalise(simpleName),                // "arrayList"
        abbreviate(simpleName),                  // "al"
        pluralise(decapitalise(simpleName)),     // "arrayLists"
    ]

    return candidates
        |> filter: c.startsWith(prefix)
        |> map:    toVariableNameItem(c)
```

### 8.4 Relevance ranking factors

```
relevance(member, expectedType, prefix, snapshot):

    score = 0

    // type match — highest weight
    if expectedType != null
       and snapshot.types.isAssignable(member.returnType, expectedType):
        score += 30

    // exact prefix case match
    if member.simpleName.startsWith(prefix) (case sensitive):
        score += 20
    elif member.simpleName.toLowerCase.startsWith(prefix.toLowerCase):
        score += 10

    // prefix length — longer match is more specific
    score += prefix.length * 2

    // accessibility
    match member.modifiers:
        PUBLIC    → score += 10
        PROTECTED → score += 5
        PACKAGE   → score += 2
        PRIVATE   → score += 0

    // non-static preferred in instance context
    if not member.isStatic: score += 5

    return score
```

---

## 9. Fallback Chain

```
sentinel parse fails or invalid
    → proposeFromIndexOnly(prefix, snapshot)
      // type names only, no member access

receiver text not resolvable in snapshot
    → textScanDeclarationAboveCursor(receiverText, content, cursorLine)
      // scan raw content for "TypeName receiverText" above cursor line
      // extract TypeName as text → elements.getTypeElement(TypeName)

snapshot too old (versionAtAttribution << current version by heuristic)
    → return proposals with isIncomplete = true
      // client will re-request after debounce fires

all resolution fails
    → return empty list, isIncomplete = true
      // do not return error — empty completion is always valid
```

---

## 10. Logging

### 10.1 Structured completion record (one per request)

```json
{
  "event":               "completion",
  "requestId":           23,
  "uri":                 "MyClass.java",
  "docVersion":          42,
  "treeVersion":         41,
  "treeAgeMs":           287,
  "cacheHit":            false,
  "sentinelValid":       true,
  "sentinelContext":     "MemberAccess",
  "receiverText":        "list",
  "receiverType":        "java.util.ArrayList<java.lang.String>",
  "receiverTypeMiss":    false,
  "prefix":              "sub",
  "filterCounts": {
    "raw":               67,
    "afterVisibility":   52,
    "afterStatic":       52,
    "afterPrefix":       3,
    "afterDedup":        3
  },
  "topProposals":        ["subList(int, int)"],
  "latencyMs":           11,
  "itemCount":           3,
  "isIncomplete":        false
}
```

### 10.2 Log levels

```
Event                           Level
───────────────────────────────────────
cache hit                       TRACE
cache miss, sentinel valid      DEBUG
sentinel invalid / misplaced    WARN
receiver type miss              DEBUG
fallback to index only          WARN
all resolution failed           WARN
attribution error               ERROR
snapshot null at completion     ERROR
```

### 10.3 Never log

- Full source content or injected content
- Individual member names before prefix filtering
- Individual index entries during type-name query

---

## 11. Test Surface

The companion scenario catalogue (`java_completion_engine_design.md`) defines test cases
organised by scenario category. Mapping to design sections:

```
Test category                   Exercises
────────────────────────────────────────────────────────────────
keyword/                        §5.4 SimpleName context, §8.2 keyword table
type_ref/                       §8.2 source 3 (index), §7.1 import edit
member_access/                  §7.1 receiver resolution, §8.1 filter pipeline
constructor/                    §5.4 ConstructorCall context, §8.1
annotation/                     §5.4 AnnotationContext, §8.2
lambda/                         §7.3 SAM resolution, §5.2 tail preservation
javadoc/                        §5.4 context extraction in comment
snippet/                        §8.3 variable names, keyword table
broken_code/                    §5.2 brace counting, §5.3 validation, §9
visibility/                     §8.1 visibility filter
module_info/                    §5.4 context in module-info.java
records/                        §8.1 component accessor proposals
sealed/                         §8.2 keyword `permits`, §8.1 subtype filter
```

---

## References

### Eclipse JDT internals

- **CompletionEngine / CompletionParser design**
  https://wiki.eclipse.org/JDT_Core_Programmer_Guide/Completion
  Covers `assistNode`, `assistNodeParent`, `enclosingNode`, source range restriction,
  lambda extension problem. Directly informs §5 and §7.

- **CompletionProposal kinds**
  https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/CompletionProposal.html
  Complete enumeration of all proposal kinds. Informs §8 proposal categories.

- **ICodeAssist / codeComplete API**
  https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/guide/jdt_api_codeassist.htm
  Relevance model, visibility options, `CompletionContext`. Informs §8.4.

- **JDT-LS index performance**
  https://github.com/eclipse-jdtls/eclipse.jdt.ls/issues/1846
  `DiskIndex` bottleneck on type-name completion. Informs JAR index design in §8.2.

- **JDT-LS CHANGELOG**
  https://github.com/eclipse-jdtls/eclipse.jdt.ls/blob/main/CHANGELOG.md
  History of edge cases: lambda completion flakiness, unresolved type assist,
  snippet resolve. Informs §9 fallback chain and §11 test surface.

### NetBeans completion architecture

- **Editor Completion architecture Q&A**
  https://bits.netbeans.org/dev/javadoc/org-netbeans-modules-editor-completion/architecture-summary.html
  Threading model, refresh vs re-query, three result surfaces, gesture logging.
  Informs §6 and §10.

- **CompletionProvider SPI**
  https://bits.netbeans.org/dev/javadoc/org-netbeans-modules-editor-completion/org/netbeans/spi/editor/completion/CompletionProvider.html
  `createTask`, `getAutoQueryTypes`, query type taxonomy. Informs §4 auto-trigger rules.

- **CompletionItem SPI**
  https://bits.netbeans.org/dev/javadoc/org-netbeans-modules-editor-completion/org/netbeans/spi/editor/completion/CompletionItem.html
  `getInsertPrefix` semantics per proposal type. Informs §8 item construction.

### Oracle javac API

- **`javax.tools` package**
  https://docs.oracle.com/en/java/docs/jdk/api/java.compiler/javax/tools/package-summary.html
  `JavaCompiler`, `StandardJavaFileManager`, `DiagnosticCollector`.
  Core of §5 injection and §3 `TreeSnapshot`.

- **`com.sun.source.tree` package**
  https://docs.oracle.com/en/java/docs/jdk/api/java.compiler/com/sun/source/tree/package-summary.html
  All `Tree` node kinds used in §5.4 context extraction.

- **`com.sun.source.util.Trees`**
  https://docs.oracle.com/en/java/docs/jdk/api/java.compiler/com/sun/source/util/Trees.html
  `getPath`, `getElement`, `getTypeMirror`, `getScope`.
  What works vs what requires attribution (§5.1).

- **`javax.lang.model.util.Elements`**
  https://docs.oracle.com/en/java/docs/jdk/api/java.compiler/javax/lang/model/util/Elements.html
  `getAllMembers`, `getTypeElement`. Primary API for §7 and §8.

- **`javax.lang.model.util.Types`**
  https://docs.oracle.com/en/java/docs/jdk/api/java.compiler/javax/lang/model/util/Types.html
  `asMemberOf`, `asElement`, `isAssignable`. Used in §7.2 lambda resolution and §8.4 ranking.

### LSP specification

- **textDocument/completion**
  https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_completion
  `CompletionList`, `isIncomplete`, `CompletionItem` fields, `triggerKind`.
  Informs §6 response shape.

- **$/cancelRequest**
  https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#cancelRequest
  Cancellation protocol. Informs §6.3.

- **CompletionItem**
  https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#completionItem
  `insertTextFormat`, `additionalTextEdits`, `documentation`. Informs §8 item construction.

### Companion documents

- **Completion scenario catalogue and test directory layout**
  `java_completion_engine_design.md` (produced in this session)
  Sections 4.1–4.15 map directly to test cases. Section 5 maps design choices to
  test expectations. Section 6 gives suggested directory layout.
