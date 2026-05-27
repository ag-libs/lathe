# Completion Engine Implementation Guide
### For `lathe` Java LSP Server

---

## 1. Purpose

This document describes how to implement `textDocument/completion` in `lathe`. It is written
for an agentic code generator. It describes exactly what to build, in what order, and what
not to build. It assumes the following are already present and working: document
synchronisation (`didOpen`, `didChange`, `didClose`), the per-module attribution pipeline
producing a `CompilationUnitTree`, and a JAR index queryable by simple-name prefix.

---

## 2. Core Idea

There are three layers. Each layer answers a different question. No layer can answer the
other's question.

**Layer 1 — Live buffer (`f.content`)**
What is the user looking at right now. A plain string. Always current.
Answers: where is the cursor, what has the user typed.

**Layer 2 — Sentinel parse**
A 10ms parse of a synthetic version of the live buffer with the cursor token replaced by
`__LATHE_SENTINEL__`. Answers: what is the syntactic structure around the cursor — is the cursor
after a dot, inside an argument list, starting a variable declaration.

**Layer 3 — Attributed snapshot (`f.snapshot`)**
A fully attributed `CompilationUnitTree` built in the background every 500ms of idle time.
Answers: what type does `list` have, what members does `ArrayList` have, is this method
visible from here.

These three layers are always separate. The live buffer feeds the sentinel parse. The
sentinel parse extracts text (receiver name, enclosing class name). That text is looked up
in the attributed snapshot to get types. Types drive proposal generation.

```
f.content ──► backward scan ──► prefix, context, receiver text
          ──► forward scan  ──► unclosed paren/brace counts
          ──► inject        ──► injected string
                                    │
                                    ▼
                             sentinel parse (10ms)
                                    │
                             ancestor walk
                                    │
                             SentinelResult
                             (receiver text, context,
                              enclosing names)
                                    │
                                    ▼
                             f.snapshot
                             (Elements, Types)
                                    │
                             TypeMirror, members
                                    │
                                    ▼
                             CompletionList
```

---

## 3. What Is Already in lathe

Do not re-implement any of the following.

```
✓ Map<URI, ?> files              per-file state store
✓ f.content                      live buffer string
✓ f.version                      LSP document version integer
✓ applyEdits(content, changes)   incremental text edit application
✓ attribution pipeline           produces CompilationUnitTree per module
✓ debounce mechanism             schedules attribution after 500ms idle
✗ JAR index                      not yet built — future work; fallback returns empty list until available
✓ didOpen / didChange / didClose handlers
✓ diagnostic publishing
```

---

## 4. New State

Two new fields on `OpenDocument`. Nothing else is new at the file level.

### 4.1 TreeSnapshot

Replaces however the attributed tree is currently stored. Groups all javac context objects
that belong together into one immutable record.

```
TreeSnapshot {
    CompilationUnitTree tree
    Trees               trees
    Elements            elements
    Types               types
    String              contentAtAttribution   // f.content at time of attribution
    int                 versionAtAttribution   // f.version at time of attribution
    Instant             builtAt
    List<Diagnostic>    diagnostics
}
```

**Critical rule:** all five javac objects belong to the same javac context. They are
created together and replaced together. Never use `elements` from one snapshot with `tree`
from another.

Stored as `volatile TreeSnapshot snapshot` on `OpenDocument`. The attribution thread is the
only writer. All other code is read-only.

### 4.2 SentinelResult

The output of one sentinel parse. Cached so that subsequent keystrokes on the same token
cost zero parses.

```
SentinelResult {

    // ── from backward scan ─────────────────────────────────────
    String   prefix          // token being completed: "sub", "to", "Arr"
    int      tokenStart      // offset in f.content where prefix begins
    String   receiverText    // text left of '.': "list", "s" — null if no dot
    Context  context         // STATEMENT or EXPRESSION
    int      cursorLine      // line number of cursor

    // ── from injection ─────────────────────────────────────────
    String   injectedContent // full string handed to javac

    // ── from sentinel parse ancestor walk ──────────────────────
    SentinelContext sentinelContext
                             // MemberAccess, SimpleName, VariableDeclaration,
                             // ArgumentPosition, ConstructorCall,
                             // AnnotationContext, LambdaBody
    String   enclosingClass  // text name: "MyClass"
    String   enclosingMethod // text name: "process" — null if static initialiser
    String   declaredTypeText// VariableDeclaration only: "ArrayList<String>"
    int      argIndex        // ArgumentPosition only: 0, 1, 2 ...
    String   enclosingReceiver  // ArgumentPosition only: "list"
    String   enclosingMethodName// ArgumentPosition only: "forEach"
    int      lambdaParamIndex   // LambdaBody only: 0

    // ── validity ───────────────────────────────────────────────
    boolean  valid           // false if sentinel landed at wrong line
    int      docVersion      // f.version at time this result was built
}
```

Stored as `SentinelResult lastSentinel` on `OpenDocument`. Null means invalid. Always check
`lastSentinel.docVersion == f.version` before use.

---

## 5. On Every didChange

Three steps. Always in this order. No parse, no type resolution, no sentinel build.

```
on didChange(uri, changes, newVersion):

    // 1. update live buffer — always first
    f.content = applyEdits(f.content, changes)
    f.version = newVersion

    // 2. invalidate sentinel cache if syntactic context changed
    char = lastCharOf(changes)
    if char in { '.', '(', ')', ' ', '{', '}', '@', '\n', ';', '[', ']', ':' }:
        f.lastSentinel = null
    // word characters do not invalidate — only the prefix changed

    // 3. restart debounce
    cancel(f.debounceHandle)
    f.debounceHandle = schedule(500ms, () -> attribute(uri))
```

`didChange` is always sub-millisecond. It never parses, never attributes, never touches
the sentinel.

---

## 6. The Two Scans

Both scans operate on `f.content` only. No javac. No tree. Called at the start of every
slow-path completion request.

### 6.1 Backward Scan

Walk left from the cursor. Extracts prefix, tokenStart, context, and receiver text in one
pass.

```
backwardScan(content, cursorOffset):

    // phase 1: extract prefix
    tokenStart = cursorOffset
    while tokenStart > 0 and isWordChar(content[tokenStart - 1]):
        tokenStart -= 1
    prefix = content[tokenStart .. cursorOffset]

    // phase 2: determine context
    // walk left from tokenStart tracking unmatched delimiters
    parenDepth   = 0
    bracketDepth = 0
    i = tokenStart - 1

    // skip the character immediately left (may be '.' for member access)
    if i >= 0 and content[i] == '.':
        hasDot = true
        i -= 1
    else:
        hasDot = false

    while i >= 0:
        c = content[i]
        match c:
            ')' → parenDepth++
            '(' → parenDepth--
                  if parenDepth < 0: context = EXPRESSION; break
            ']' → bracketDepth++
            '[' → bracketDepth--
                  if bracketDepth < 0: context = EXPRESSION; break
            ';' → context = STATEMENT; break
            '{' → if parenDepth == 0: context = STATEMENT; break
            ':' → context = EXPRESSION; break   // ternary or switch arrow
        i -= 1

    if no break: context = STATEMENT   // reached start of block

    // phase 3: extract receiver text (only if hasDot)
    receiverText = null
    if hasDot:
        // collect expression left of the dot, respecting paren balance
        receiverEnd = tokenStart - 1   // position of '.'
        receiverText = collectReceiver(content, receiverEnd - 1)

    return prefix, tokenStart, context, receiverText, cursorLine


collectReceiver(content, from):
    // walk left collecting chars, respecting paren/bracket balance
    // stop at whitespace, ';', '{' at depth 0
    depth = 0
    i = from
    while i >= 0:
        c = content[i]
        if c in { ')', ']' }: depth++
        if c in { '(', '[' }: depth--
        if depth < 0: break
        if depth == 0 and c in { ' ', '\t', '\n', ';', '{', ',' }: break
        i -= 1
    return content[i+1 .. from+1].trim()
```

### 6.2 Forward Scan

Count unmatched open delimiters in the entire file. Used only during injection to know
what to append.

```
forwardScan(content):

    parenDepth  = 0
    braceDepth  = 0
    inString    = false
    inLineComment = false
    inBlockComment = false
    i = 0

    while i < content.length:

        // comment and string tracking
        if inLineComment:
            if content[i] == '\n': inLineComment = false
            i++; continue
        if inBlockComment:
            if content[i..i+2] == '*/': inBlockComment = false; i += 2; continue
            i++; continue
        if content[i..i+2] == '//': inLineComment = true; i += 2; continue
        if content[i..i+2] == '/*': inBlockComment = true; i += 2; continue
        if content[i] == '"': inString = !inString
        if inString: i++; continue

        // count delimiters
        if content[i] == '(': parenDepth++
        if content[i] == ')': parenDepth--
        if content[i] == '{': braceDepth++
        if content[i] == '}': braceDepth--
        i++

    return max(0, parenDepth), max(0, braceDepth)
```

### 6.3 What the Scans Do Not Cover

The following cases are out of scope for the initial implementation. The fallback chain
(§12) handles them gracefully by returning index-only proposals or an empty list.

```
Case                            Example
──────────────────────────────────────────────────────────
String templates (Java 21)      STR."hello \{list.su|}"
Complex nested lambdas          deeply nested lambda chains
Switch expression arrow         case 1 -> list.su|
Anonymous class enclosure       correct outer class resolution
Record compact constructor      constructor with no name
Multi-catch parameter type      catch (IOException | SQLException e)
```

---

## 7. Building the Injected String

Inputs: scan results from §6. Output: a string that javac can parse.

```
inject(content, cursorOffset, prefix, tokenStart, context, unclosedParens, unclosedBraces):

    semicolon = (context == STATEMENT) ? ";" : ""

    injected = content[0 .. tokenStart]
             + "__LATHE_SENTINEL__"
             + semicolon
             + content[cursorOffset ..]          // preserve tail — lambdas need
                                                  // their closing ); to be visible
             + ")".repeat(unclosedParens)         // balance parens
             + "}".repeat(unclosedBraces)         // balance braces

    return injected
```

**Why the tail is preserved:** if the cursor is inside a lambda passed to a method call,
the closing `);` of the outer call exists later in the file. Javac needs to see it to
parse the lambda expression correctly. Discarding the tail would make the lambda
un-parseable.

**Why the semicolon decision matters:**

```
STATEMENT context:  list.__LATHE_SENTINEL__;        ← valid statement
EXPRESSION context: foo(list.__LATHE_SENTINEL__)    ← valid argument, tail provides ')'
                                               adding ';' here breaks the parse
```

---

## 8. Sentinel Parse

One parse-only javac invocation on the injected string. No attribution.

```
parseSentinel(injectedContent, expectedLine):

    // parse — no attribution, no classpath resolution needed
    cu = parseOnlyCompiler.parse(toFileObject(injectedContent))

    // find __LATHE_SENTINEL__ node
    sentinelPath = new TreeScanner() {
        visitMemberSelect(node):
            if node.identifier == "__LATHE_SENTINEL__": return currentPath
        visitIdentifier(node):
            if node.name == "__LATHE_SENTINEL__": return currentPath
    }.scan(cu)

    if sentinelPath == null:
        return invalid

    // validate position
    actualLine = cu.lineMap.getLineNumber(
        sourcePositions.getStartPosition(cu, sentinelPath.leaf))
    if actualLine != expectedLine:
        return invalid   // javac recovery moved the node

    // extract context from ancestor chain
    return extractContext(sentinelPath)


extractContext(sentinelPath):

    parent = sentinelPath.parentPath.leaf

    match parent type:

        MemberSelectTree (identifier == "__LATHE_SENTINEL__"):
            sentinelContext = MemberAccess
            receiverText    = parent.expression.toString()

        VariableTree (name == "__LATHE_SENTINEL__"):
            sentinelContext  = VariableDeclaration
            declaredTypeText = parent.type.toString()

        MethodInvocationTree (args contains sentinel):
            sentinelContext      = ArgumentPosition
            argIndex             = indexOf(sentinel, parent.args)
            enclosingReceiver    = parent.methodSelect.expression.toString()
            enclosingMethodName  = parent.methodSelect.identifier.toString()

        NewClassTree:
            sentinelContext = ConstructorCall

        AnnotationTree:
            sentinelContext = AnnotationContext

        LambdaExpressionTree:
            sentinelContext  = LambdaBody
            lambdaParamIndex = indexOf(sentinel param, parent.params)

        ExpressionStatementTree:
            sentinelContext = SimpleName   // default

    // walk further up for enclosing class and method
    for node in sentinelPath:
        if node is MethodTree:  enclosingMethod = node.name.toString()
        if node is ClassTree:   enclosingClass  = node.simpleName.toString()

    return SentinelResult { valid=true, sentinelContext, enclosingClass,
                            enclosingMethod, ... }
```

---

## 9. Completion Request — Fast Path

Check this before doing anything else on every completion request.

```
fastPath(f, request):

    if f.lastSentinel == null:                          return MISS
    if f.lastSentinel.docVersion != f.version:          return MISS
    if f.lastSentinel.cursorLine != request.position.line: return MISS
    if not isWordChar(lastCharOf(request)):             return MISS

    // cache hit — only the prefix changed
    newPrefix = extractPrefix(f.content, request.position)
    return propose(f.lastSentinel, newPrefix, f.snapshot)
    // latency: <1ms, zero parses
```

---

## 10. Completion Request — Slow Path

Called when the fast path returns MISS.

```
slowPath(f, request, cursorOffset):

    // 1. scan
    prefix, tokenStart, context, receiverText, cursorLine
        = backwardScan(f.content, cursorOffset)
    unclosedParens, unclosedBraces
        = forwardScan(f.content)

    // 2. inject
    injected = inject(f.content, cursorOffset, prefix, tokenStart,
                      context, unclosedParens, unclosedBraces)

    // 3. parse — on CompletionThread, awaited here
    sentinel = parseSentinel(injected, cursorLine)
    sentinel.prefix     = prefix
    sentinel.docVersion = f.version
    sentinel.cursorLine = cursorLine

    if not sentinel.valid:
        return fallback(prefix, f.snapshot)      // §12 level 1

    // 4. cache
    f.lastSentinel = sentinel

    // 5. resolve and propose
    return propose(sentinel, prefix, f.snapshot)


propose(sentinel, prefix, snapshot):

    match sentinel.sentinelContext:

        MemberAccess:
            receiverType = resolveReceiverType(sentinel, snapshot)  // §11
            if receiverType == null:
                return fallback(prefix, snapshot)                    // §12 level 2
            return proposeMemberAccess(receiverType, prefix,
                                       isCapitalised(sentinel.receiverText),
                                       snapshot)                     // §13.1

        SimpleName:
            return proposeSimpleName(sentinel, prefix, snapshot)     // §13.2

        VariableDeclaration:
            return proposeVariableNames(sentinel.declaredTypeText,
                                        prefix, snapshot)            // §13.3

        ArgumentPosition:
            expectedType = resolveParamType(sentinel, snapshot)
            return proposeSimpleName(sentinel, prefix, snapshot,
                                     expectedType)                   // §13.2 + rank

        ConstructorCall:
            return proposeConstructors(prefix, snapshot)

        AnnotationContext:
            return proposeAnnotationTypes(prefix, snapshot)

        LambdaBody:
            lambdaParamType = resolveLambdaParamType(sentinel, snapshot) // §11.3
            if lambdaParamType == null:
                return proposeSimpleName(sentinel, prefix, snapshot)
            return proposeMemberAccess(lambdaParamType, prefix, false, snapshot)
```

---

## 11. Type Resolution

All resolution reads from `snapshot` only. No parse. No attribution. No javac invocation.

### 11.1 Receiver Type Resolution

```
resolveReceiverType(sentinel, snapshot):

    text = sentinel.receiverText

    match text:
        "this"  → return enclosingClassType(sentinel.enclosingClass, snapshot)
        "super" → return superclassOf(enclosingClassType(...))

        isCapitalised(text):
            // assume type name — static access
            el = snapshot.elements.getTypeElement(text)
            if el != null: return el.asType()

        else:
            // assume local variable or parameter
            type = scanForLocalDeclaration(text, sentinel, snapshot)
            if type != null: return type

            // fallback: field of enclosing class
            return findFieldType(text, sentinel.enclosingClass, snapshot)
```

### 11.2 Local Variable Scan

```
scanForLocalDeclaration(name, sentinel, snapshot):

    classEl  = snapshot.elements.getTypeElement(sentinel.enclosingClass)
    methodEl = findMethod(classEl, sentinel.enclosingMethod, snapshot)
    if methodEl == null: return null

    methodTree = snapshot.trees.getTree(methodEl)

    return new TreeScanner<TypeMirror>() {
        visitVariable(node):
            if node.name.toString() == name:
                nodeLine = getLineNumber(snapshot, node)
                if nodeLine < sentinel.cursorLine:
                    el = snapshot.trees.getElement(
                             snapshot.trees.getPath(snapshot.tree, node))
                    if el != null: return el.asType()
            return super.visitVariable(node)
    }.scan(methodTree.body)
```

Only declarations above the cursor line are considered — a variable is not in scope before
its declaration.

### 11.3 Lambda Parameter Type Resolution

```
resolveLambdaParamType(sentinel, snapshot):

    receiverType = resolveReceiverType(
                       syntheticSentinel(sentinel.enclosingReceiver), snapshot)
    if receiverType == null: return null

    methodEl = resolveMethod(sentinel.enclosingMethodName,
                              receiverType, sentinel.argIndex, snapshot)
    if methodEl == null: return null

    // get functional interface at the lambda's argument position
    paramType = snapshot.types.asMemberOf(
                    (DeclaredType) receiverType, methodEl)
                .parameterTypes[sentinel.argIndex]

    // find the SAM method
    samEl   = findSAM(paramType, snapshot)
    if samEl == null: return null

    samType = snapshot.types.asMemberOf((DeclaredType) paramType, samEl)

    return samType.parameterTypes[sentinel.lambdaParamIndex]
```

---

## 12. Fallback Chain

Never return an error. Always return a possibly-empty list.

```
Level 1 — sentinel parse failed or invalid
    proposals = index.queryByPrefix(prefix)
                    |> map: toTypeRefItem(fqn, isImported(fqn, snapshot))
    return CompletionList(isIncomplete=true, items=proposals)

Level 2 — receiver type not resolvable
    // text-scan the raw content above the cursor for a declaration
    typeName = textScanDeclaration(sentinel.receiverText, f.content, cursorLine)
    if typeName != null:
        el = snapshot.elements.getTypeElement(typeName)
        if el != null:
            return proposeMemberAccess(el.asType(), prefix, false, snapshot)
    // give up on member access, fall to index
    return level1(prefix, snapshot)

Level 3 — snapshot is null (attribution not yet complete)
    proposals = index.queryByPrefix(prefix)
                    |> map: toTypeRefItem(...)
    return CompletionList(isIncomplete=true, items=proposals)

Level 4 — all resolution failed
    return CompletionList(isIncomplete=true, items=[])
```

---

## 13. Proposal Generation

### 13.1 Member Access Proposals

```
proposeMemberAccess(receiverType, prefix, isStaticAccess, snapshot):

    receiverEl = snapshot.types.asElement(receiverType)
    if receiverEl == null: return []

    return snapshot.elements.getAllMembers((TypeElement) receiverEl)
        |> filter: isVisible(m, enclosingClass, snapshot)
        |> filter: isStatic(m) == isStaticAccess
        |> filter: not isBridge(m)
        |> filter: not isSynthetic(m)
        |> filter: m.simpleName.toString().startsWith(prefix)
        |> sortBy: relevance(m, null, prefix) descending
        |> map:    toCompletionItem(m, receiverType, snapshot)
```

### 13.2 Simple Name Proposals

Four sources merged and deduped. Order matters — sources earlier in the list win on dedup.

```
proposeSimpleName(sentinel, prefix, snapshot, expectedType = null):

    proposals = []

    // 1. locals and parameters visible above cursor
    proposals += scanAllLocalDeclarations(sentinel, prefix, snapshot)

    // 2. members of enclosing class
    enclosingEl = snapshot.elements.getTypeElement(sentinel.enclosingClass)
    proposals += snapshot.elements.getAllMembers(enclosingEl)
                     |> filter: simpleName.startsWith(prefix)
                     |> filter: isVisible(m, sentinel.enclosingClass, snapshot)

    // 3. type names from JAR index
    proposals += index.queryByPrefix(prefix)
                     |> map: toTypeRefItem(fqn, isImported(fqn, snapshot))

    // 4. keywords valid at this context
    proposals += keywordsFor(sentinel.sentinelContext, prefix)

    return dedup(proposals)
        |> sortBy: relevance(m, expectedType, prefix) descending
```

### 13.3 Variable Name Suggestions

```
proposeVariableNames(declaredTypeText, prefix, snapshot):

    typeEl = snapshot.elements.getTypeElement(stripGenerics(declaredTypeText))
    if typeEl == null: return []

    simpleName = typeEl.simpleName.toString()   // "ArrayList"

    candidates = [
        decapitalise(simpleName),               // "arrayList"
        abbreviate(simpleName),                 // "al"
        pluralise(decapitalise(simpleName)),    // "arrayLists"
    ]

    return candidates
        |> filter: startsWith(prefix)
        |> map:    toVariableNameItem
```

### 13.4 Relevance Scoring

```
Factor                                          Points
────────────────────────────────────────────────────────
Return type assignable to expectedType          +30
Exact case prefix match                         +20
Case-insensitive prefix match                   +10
Each character of prefix matched                +2 per char
PUBLIC modifier                                 +10
PROTECTED modifier                              +5
PACKAGE modifier                                +2
Non-static in instance context                  +5
```

---

## 14. Cache Invalidation Rules

### Characters that invalidate `lastSentinel`

```
Character   Reason
─────────────────────────────────────────────────────────────
'.'         new MemberAccess context
'('         new ArgumentPosition context
')'         context closed
' '         possible VariableDeclaration boundary
'{'         new block scope
'}'         block closed
'@'         AnnotationContext
'\n'        new line — always re-establish context
';'         statement ended
'['         array index context
']'         array index closed
':'         ternary or switch arrow
```

### Characters that do not invalidate

```
Character   Reason
─────────────────────────────────────────────────────────────
a-z A-Z     extending the current prefix
0-9         extending the current prefix
_           extending the current prefix
```

Word characters never change the syntactic context — they only extend the prefix. The
existing `lastSentinel` remains valid. The next completion request is a cache hit and only
needs to re-filter by the longer prefix.

---

## 15. Logging

### 15.1 Structured Completion Record

One record per completion request. Written at INFO on cache miss, TRACE on cache hit.

```json
{
  "event":            "completion",
  "requestId":        23,
  "uri":              "MyClass.java",
  "docVersion":       42,
  "treeVersion":      41,
  "treeAgeMs":        287,
  "cacheHit":         false,
  "sentinelValid":    true,
  "sentinelContext":  "MemberAccess",
  "receiverText":     "list",
  "receiverType":     "java.util.ArrayList<java.lang.String>",
  "receiverTypeMiss": false,
  "prefix":           "sub",
  "filterCounts": {
    "raw":            67,
    "afterVisibility":52,
    "afterStatic":    52,
    "afterPrefix":    3,
    "afterDedup":     3
  },
  "topProposals":     ["subList(int, int)"],
  "latencyMs":        11,
  "itemCount":        3,
  "isIncomplete":     false,
  "fallbackLevel":    0
}
```

### 15.2 Log Levels

```
Event                               Level
────────────────────────────────────────────
cache hit                           TRACE
cache miss, sentinel valid          DEBUG
sentinel invalid or misplaced       WARN
receiver type miss, text-scan used  DEBUG
fallback level 1 (index only)       WARN
fallback level 3 (no snapshot)      WARN
all resolution failed               WARN
attribution error                   ERROR
snapshot null at completion time    ERROR
cancellation received               DEBUG
```

### 15.3 Never Log

- Full `f.content` or `injectedContent`
- Individual member names before prefix filtering
- Individual JAR index entries during type-name query

---

## 16. Known Gaps

The following cases are out of scope. The fallback chain returns index-only proposals or
an empty list for all of them — no error, no crash.

```
Gap                         Example                         Fallback
────────────────────────────────────────────────────────────────────────────────
String templates            STR."hello \{list.su|}"         empty list
Complex nested lambdas      stream().map().filter()...       index-only
Switch expression arrow     case 1 -> list.su|              index-only
Anonymous class enclosure   wrong outer class resolved       partial proposals
Record compact constructor  no method name for scope walk    index-only
Multi-catch parameter       catch (IOE | SQLE e) → e.su|   index-only
Casted receiver narrowing   ((String) obj).su|              Object members only
```

Each gap is a future improvement. The structured log (`fallbackLevel > 0`) identifies
which requests hit a gap in production, providing a priority-ordered list for future work.

---

## 17. References

### Eclipse JDT internals
- **CompletionEngine / CompletionParser design**
  https://wiki.eclipse.org/JDT_Core_Programmer_Guide/Completion
  Authoritative description of how a production Java completion engine handles partial
  ASTs, error recovery, and the assistNode family. Directly informs §7 and §8.

- **CompletionProposal kinds**
  https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/CompletionProposal.html
  Complete enumeration of proposal kinds. Informs §10 proposal categories.

- **ICodeAssist / codeComplete API**
  https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/guide/jdt_api_codeassist.htm
  Relevance model, visibility options. Informs §13.4.

- **JDT-LS CHANGELOG**
  https://github.com/eclipse-jdtls/eclipse.jdt.ls/blob/main/CHANGELOG.md
  Years of production edge cases. Informs §16 known gaps.

### NetBeans completion architecture
- **Architecture Q&A**
  https://bits.netbeans.org/dev/javadoc/org-netbeans-modules-editor-completion/architecture-summary.html
  Threading model, refresh vs re-query distinction. Informs §9 fast/slow path split.

### Oracle javac API
- **`com.sun.source.tree` package**
  https://docs.oracle.com/en/java/docs/jdk/api/java.compiler/com/sun/source/tree/package-summary.html
  All Tree node kinds used in §8 context extraction.

- **`com.sun.source.util.Trees`**
  https://docs.oracle.com/en/java/docs/jdk/api/java.compiler/com/sun/source/util/Trees.html
  What is available on a parse-only tree vs attributed tree. Informs §4 and §11.

- **`javax.lang.model.util.Elements`**
  https://docs.oracle.com/en/java/docs/jdk/api/java.compiler/javax/lang/model/util/Elements.html
  `getAllMembers`, `getTypeElement`. Primary API for §11 and §13.

- **`javax.lang.model.util.Types`**
  https://docs.oracle.com/en/java/docs/jdk/api/java.compiler/javax/lang/model/util/Types.html
  `asMemberOf`, `asElement`, `isAssignable`. Used in §11.3 and §13.4.

### LSP specification
- **textDocument/completion**
  https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_completion

- **$/cancelRequest**
  https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#cancelRequest

### Companion documents
- **Completion scenario catalogue** — `java_completion_engine_design.md`
  Test cases for all proposal categories. Sections 4.1–4.15 map to §13 proposal types.

- **High level design** — `completion_engine_hld.md`
  Architectural overview this document refines into implementation guidance.
