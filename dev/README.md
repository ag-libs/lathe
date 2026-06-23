# Lathe — Dev Tooling

Scripts for building and testing the server locally against real projects.
All files live in `dev/` and are never shipped with the distribution.

---

## Scripts

| File | Purpose |
|---|---|
| `nvim.sh` | Build server + open Neovim with Lathe attached |
| `lsp.py` | Python LSP client library and CLI diagnostics tool |
| `explore.py` | Interactive LSP shell — explore any file like an engineer would |

---

## nvim.sh — interactive testing in a real editor

```bash
./dev/nvim.sh /home/ag-libs/git/helidon/scheduling/src/main/java/io/helidon/scheduling/Scheduling.java
```

On each launch it:
1. Builds `lathe-server` and its dependencies from source
2. Opens Neovim with the built classes (no installed JAR needed)
3. Starts the LSP server automatically on Java files

**Debug port** (attach a debugger):
```bash
LATHE_DEBUG_PORT=5005 ./dev/nvim.sh path/to/File.java
```

---

## explore.py — interactive LSP shell

`explore.py` opens a single Java file in the Lathe server and drops into a
read-eval-print loop.  You interact with the file the same way a software
engineer would: look at the code, position the cursor at an interesting spot,
ask for completions or hover information, temporarily inject a new line to
see what the engine suggests there, then reset.

No probes are hard-coded.  The file and the questions are entirely up to you.

### Usage

**Interactive REPL**
```
python3 dev/explore.py path/to/File.java
```

**Single inline command** (remaining args are joined as one command line):
```
python3 dev/explore.py File.java complete after "handler.getServletContext()."
python3 dev/explore.py File.java hover "MongoClients"
python3 dev/explore.py File.java inject "MongoClients."
```

**Piped / sub-agent** (one command per stdin line, no prompt):
```
printf 'grep setAttribute\ncomplete after "handler.getServletContext()."\n' \
    | python3 dev/explore.py AbstractServerFactory.java
```

### Commands

| Command | What it does |
|---|---|
| `show [<line>]` | Print file with line numbers, centred on `<line>` (1-based) if given |
| `grep <pattern>` | Show every line containing `<pattern>` with its line number |
| `complete <line>:<col>` | Completions at the given 0-based position |
| `complete after <text>` | Find first occurrence of `<text>`, complete right after it |
| `accept <line>:<col> <selector>` | Select one completion item and show the accepted edit |
| `accept after <text> <selector>` | Accept a completion right after the first matching text |
| `accept inject <code> [at <line>] <selector>` | Inject a temporary line, accept one completion, and show the edit |
| `hover <line>:<col>` | Hover info (type, docs) at the given 0-based position |
| `hover <text>` | Hover info at the first occurrence of `<text>` |
| `definition <line>:<col>` | Declaration site of the symbol (alias: `def`) |
| `refs <line>:<col>` | Known call/use sites of the symbol (alias: `refs <text>`) |
| `refs <text>` | Find first occurrence of `<text>`, request references there |
| `impl <line>:<col>` | Concrete implementations of the type/method (alias: `implementation`, `impl <text>`) |
| `impl <text>` | Find first occurrence of `<text>`, request implementations there |
| `hierarchy <line>:<col>` | Full type hierarchy: item + supertypes + subtypes (alias: `hier`, `hierarchy <text>`) |
| `hierarchy <text>` | Find first occurrence of `<text>`, show its type hierarchy |
| `diagnostics` | Compiler errors and warnings (alias: `diag`) |
| `inject <code> [at <line>]` | Insert a temporary line, complete at its end |
| `reset` | Discard injected content, restore original server view |
| `log [<n>]` | Last `<n>` relevant server log lines (default 20) |
| `help` | Print the command reference |
| `quit` / `exit` / `q` | End the session |

### Assertion qualifiers

Append to `complete`, `inject`, or `refs` commands to turn observation into a
pass/fail check.  Multiple qualifiers may be combined freely.

**Completion / injection assertions** (match against completion item labels):

| Qualifier | Meaning |
|---|---|
| `expect <label> [<label> …]` | Each label must appear as a prefix of at least one returned item |
| `min <n>` | At least `n` items must be returned |
| `max <n>` | At most `n` items may be returned |
| `filter <prefix>` | Every item's label must start with `prefix` (case-insensitive) |

**Reference assertions** (match against result file paths):

| Qualifier | Meaning |
|---|---|
| `expect <substr> [<substr> …]` | Each substring must appear in at least one result's file path |
| `min <n>` | At least `n` references required |
| `max <n>` | At most `n` references allowed |

On success the output line reads `[PASS]`.  On failure it reads `[FAIL]` with
a reason, and the process exits with code 1.  This makes assertions composable
in shell scripts.

```bash
# assert that setAttribute is offered after the chained call
python3 dev/explore.py AbstractServerFactory.java \
    complete after "handler.getServletContext()." expect setAttribute min 1

# assert that a member-access after injection surfaces the right factory method
python3 dev/explore.py MongoDbClient.java \
    inject "MongoClients." expect create min 1

# assert that a bare-dot with no receiver returns nothing
python3 dev/explore.py MongoDbClient.java \
    inject "." max 0

# assert that a public method has at least one cross-module reference
python3 dev/explore.py StringUtils.java refs "upper" min 1 expect "Main.java"

# assert that a private member has no references outside its file
python3 dev/explore.py StringUtils.java refs "StringUtils()" max 0

# pipe multiple reference checks in one server session
printf 'refs "upper" min 1 expect Main.java\nrefs "StringUtils()" max 0\n' \
    | python3 dev/explore.py StringUtils.java
```

### Probing accepted completion edits

`accept` inspects what selecting one completion item would do.
It prints the raw selected `CompletionItem`,
applies `textEdit` and `additionalTextEdits` in memory,
and marks the inferred post-accept cursor with `§`.

Selectors can be combined:

| Selector | Meaning |
|---|---|
| `label <label>` | Item label must equal `<label>` |
| `filter-text <text>` | Item `filterText` must equal `<text>` |
| `label-detail <detail>` | Item `labelDetails.detail` must equal `<detail>` |
| `detail-contains <text>` | Item `detail` must contain `<text>` |
| `index <n>` | Choose the nth item after filters, default `0` |

For `accept inject`,
the injected code may contain `§` to place the completion cursor before trailing text.
The marker is removed from the temporary content.

```bash
python3 dev/explore.py /path/to/File.java \
    accept inject 'LOGGER.de' at 63 filter-text debug label-detail '(String)'

python3 dev/explore.py /path/to/File.java \
    accept inject 'import static java.util.Collections.emptyL' at 43 label emptyList

python3 dev/explore.py /path/to/File.java \
    accept inject 'import static java.util.Collections.emptyL§;' at 43 label emptyList
```

### Probing find-references

`refs <text>` is the primary tool for exploring and verifying reference behaviour.
Position the cursor by naming a token — no need to know the exact line and column.
Reference searches display LSP work-done progress as candidates complete.

Long searches can be cancelled through either standard protocol route after a candidate threshold:

```bash
python3 dev/explore.py File.java refs String cancel-progress 100
python3 dev/explore.py File.java refs String cancel-request 100
```

Lifecycle probes verify bounded server exit during an active search:

```bash
python3 dev/explore.py File.java refs String shutdown-after 100
python3 dev/explore.py File.java refs String eof-after 100
```

After completion or cancellation, run `hover <text>` in the same interactive session to verify that the server remains responsive.

**What to probe:**

- **Public methods** — expect cross-module hits (`expect <filename>`), exact count (`min`/`max`)
- **Private members** — scope must not leave the file (`max 0`)
- **Package-private members** — scope must not leave the module
- **Imports** — the import statement itself should count as one reference
- **Enum constants** — `refs "CONSTANT_NAME"` should find all qualified uses
- **Annotations** — `refs "MyAnnotation"` should find annotation use sites
- **Overloaded methods** — two overloads must not bleed into each other

### Typical workflow

```
> grep getServletContext
    577      handler.getServletContext().setAttribute("org.eclipse.jetty.server.webapp...");

> complete after "handler.getServletContext()."
  completing after "handler.getServletContext()."  →  position 576:36
  ────────────────────────────────────────────────────────────────
  ...
  >>> 577    handler.getServletContext().setAttribute("org.eclipse.jetty.server...
  ────────────────────────────────────────────────────────────────
  28 item(s):
    setAttribute  [Method]  void
    getAttribute  [Method]  Object
    ...

> hover "getServletContext"
  found "getServletContext" at 576:11
  javax.servlet.ServletContext Handler.getServletContext()

> inject "MongoClients."
  injected at line 101:  "        MongoClients."
  completing at 100:20
  ────────────────────────────────────────────────────────────────
  3 item(s):
    create  [Method]  MongoClient
    createWithCustomClass  [Method]  MongoClient
    ...

> reset
  reset to original content

> quit
```

### Columns are 0-based

`complete` and `hover` take **0-based** line and column numbers, matching the
LSP wire protocol and the coordinates logged by the Lathe server.  `show` and
`grep` report **1-based** line numbers (as editors do).  `complete after <text>`
and `hover <text>` handle the conversion automatically.

---

## lsp.py — LSP client library

`lsp.py` is the shared foundation for `explore.py` and ad-hoc probes.
It handles work-done progress and wraps the Lathe server process in synchronous and cancellable asynchronous APIs.

There is intentionally no Python test suite for `dev/`.
These scripts are developer tools, not part of the Maven build or release gate.
When changing them, validate the affected path with direct commands against a real workspace.

Useful manual validations:

```bash
python3 -m py_compile dev/lsp.py dev/explore.py

LATHE_TIMEOUT=90 python3 dev/explore.py File.java refs Symbol cancel-progress 100
LATHE_TIMEOUT=90 python3 dev/explore.py File.java refs Symbol cancel-request 100
LATHE_TIMEOUT=90 python3 dev/explore.py File.java refs Symbol shutdown-after 100
LATHE_TIMEOUT=90 python3 dev/explore.py File.java refs Symbol eof-after 100
```

These probes cover progress parsing, progress-token cancellation, `$/cancelRequest` cancellation,
bounded shutdown, EOF handling, and post-cancellation responsiveness.
They do not launch Neovim and they are not a replacement for Java-side LSP request tests.

### CLI — print diagnostics for a file

```bash
python3 dev/lsp.py path/to/Foo.java
python3 dev/lsp.py path/to/Foo.java:42:10   # also prints hover at line 42, col 10 (1-based)
```

### Importable `LatheClient`

```python
import sys; sys.path.insert(0, "/home/ag-libs/git/lathe/dev")
from lsp import LatheClient, find_workspace_root
from pathlib import Path

file = Path("/home/ag-libs/git/helidon/.../Scheduling.java")
with LatheClient.start(find_workspace_root(file)) as c:
    diags   = c.open(file)
    items   = c.completion(file, line=10, col=5)   # 0-based
    hover   = c.hover(file, line=10, col=5)
    defs    = c.definition(file, line=10, col=5)
    refs    = c.references(file, line=10, col=5)
    impls   = c.implementation(file, line=10, col=5)
    hier    = c.prepare_type_hierarchy(file, line=10, col=5)
    supers  = c.type_hierarchy_supertypes(hier[0]) if hier else []
    subs    = c.type_hierarchy_subtypes(hier[0]) if hier else []
    fmt     = c.formatting(file)
    symbols = c.document_symbols(file)
    diags   = c.save(file)                          # trigger re-analysis
```

### Environment variables

| Variable | Default | Effect |
|---|---|---|
| `LATHE_DEBUG` | off | Set to `1` for verbose server logs on stderr |
| `LATHE_TIMEOUT` | `15` | Per-request timeout in seconds |

### Prerequisite: build the server

```bash
mvn install -pl lathe-server -am -DskipTests
```

Or run `./dev/nvim.sh` once — it builds automatically.
