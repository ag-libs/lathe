# Lathe — Dev Tooling

Scripts for building and testing the server locally against real projects.
All scripts live here (`dev/`) and are never shipped.

---

## Scripts

| File | Purpose |
|---|---|
| `nvim.sh` | Build server + open Neovim with Lathe attached |
| `nvim.lua` | Neovim config loaded by `nvim.sh` (not used directly) |
| `lsp.py` | Python LSP client — CLI diagnostics or importable `LatheClient` |

---

## nvim.sh — interactive testing

```bash
cd /home/ag-libs/design/lathe
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

## lsp.py — programmatic LSP testing

### CLI mode — print diagnostics

```bash
python3 dev/lsp.py path/to/Foo.java path/to/FooTest.java
```

Workspace root is auto-detected from the nearest `.lathe/` directory.

### Import mode — test specific LSP features

```python
import sys; sys.path.insert(0, "/home/ag-libs/design/lathe/dev")
from lsp import LatheClient, find_workspace_root
from pathlib import Path

file = Path("/home/ag-libs/git/helidon/scheduling/src/main/java/io/helidon/scheduling/Scheduling.java")

with LatheClient.start(find_workspace_root(file)) as c:
    # Phase 3 — diagnostics (implemented)
    diags = c.open(file)
    c.change(file, new_content)     # simulate edit (no diagnostics wait)
    diags = c.save(file)            # full pass with AP

    # Phase 4 — result cache (not yet implemented)
    hover   = c.hover(file, line=10, col=5)
    fmt     = c.formatting(file)
    symbols = c.document_symbols(file)

    # Phase 5 — go-to-definition (not yet implemented)
    defs = c.definition(file, line=10, col=5)

    # Phase 6 — type completion (not yet implemented)
    items = c.completion(file, line=10, col=5)

    # Phase 8 — find references (not yet implemented)
    refs = c.references(file, line=10, col=5)
```

Unimplemented features raise `RuntimeError` with the server's `UnsupportedOperationException` —
that's the expected signal until the phase is done.

### Environment variables

| Variable | Default | Effect |
|---|---|---|
| `JAVA_HOME` | `/opt/jdk` | JDK used to run the server |
| `LATHE_DEBUG` | off | Set to `1` for verbose server logs on stderr |
| `LATHE_TIMEOUT` | `15` | Per-request timeout in seconds |

### Prerequisite: build the server

The Python client uses `lathe-server/target/classes` directly (same as `nvim.sh`).
Build once before using:

```bash
cd /home/ag-libs/design/lathe
mvn install -pl lathe-server -am -DskipTests
mvn dependency:copy-dependencies -pl lathe-server -DincludeScope=runtime -DoutputDirectory=target/dependency -q
```

Or just run `./dev/nvim.sh` once — it does both steps automatically.

---

## Tested files (known good)

These files compile cleanly with 0 diagnostics and are good regression anchors:

```bash
python3 dev/lsp.py \
  /home/ag-libs/git/helidon/scheduling/src/main/java/io/helidon/scheduling/Scheduling.java \
  /home/ag-libs/git/helidon/scheduling/src/test/java/io/helidon/scheduling/CronSchedulingTest.java \
  /home/ag-libs/git/helidon/common/key-util/src/test/java/io/helidon/common/pki/KeyConfigTest.java
```
