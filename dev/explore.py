#!/usr/bin/env python3
"""
explore.py — Interactive LSP shell for Lathe.

Opens a Java source file in the Lathe language server and provides a
read-eval-print loop for exploring completions, hover, definitions, and
diagnostics — the same workflow a software engineer follows while editing.

Suitable for interactive use in a terminal, for single-command invocation
from scripts, and for piped / sub-agent use.

USAGE
-----
Interactive REPL:
    python3 dev/explore.py <file>

Single inline command (remaining argv joined as one command line):
    python3 dev/explore.py <file> complete after "config.url()"
    python3 dev/explore.py <file> complete after "MongoClients." expect create min 1
    python3 dev/explore.py <file> inject "MongoClients." expect create
    python3 dev/explore.py <file> accept inject "LOGGER.de" at 63 filter-text debug label-detail "(String)"

Piped / sub-agent (one command per line on stdin):
    printf 'grep setAttribute\\ncomplete after "getServletContext()." expect setAttribute\\n' \\
        | python3 dev/explore.py AbstractServerFactory.java

Exit code is 1 if any assertion (expect / min / max / filter) fails.

COMMANDS
--------
show [<line>]
    Print the file with line numbers, optionally centred on <line> (1-based).

grep <pattern>
    Show every line containing <pattern> with its 1-based line number.

complete <line>:<col> [<assertions>]
    Request completions at the given 0-based line and column.

complete after <text> [<assertions>]
    Find the first occurrence of <text> in the current file view and request
    completions at the character immediately following it.

hover <line>:<col>
    Show hover information (type, docs) at the given 0-based position.

hover <text>
    Show hover information at the first occurrence of <text>.

definition <line>:<col>   (alias: def <line>:<col>)
    Show the declaration site of the symbol at the given 0-based position.

refs <line>:<col> [assertions]
    List known call/use sites of the symbol at the given 0-based position.

refs <text> [assertions]
    Find the first occurrence of <text> in the file and request references there.
    Supports the same assertion qualifiers as complete, except filter:
      expect <substr>  at least one result path must contain substr
      min <n>          at least n references required
      max <n>          at most n references allowed

diagnostics   (alias: diag)
    List errors and warnings the compiler reports for this file.

inject <code> [at <line>] [<assertions>]
    Temporarily insert <code> as a new line into the file — at the start of
    the first method body when no line is given (1-based otherwise) — then
    immediately request completions at the end of the inserted text.
    The file on disk is never modified.  Any previous injection is discarded
    first.

accept <line>:<col> <selector>
accept after <text> <selector>
accept inject <code> [at <line>] <selector>
    Request completion, select one item, print its raw CompletionItem, apply
    textEdit/additionalTextEdits in memory, and show the resulting text with
    the inferred post-accept cursor marked as §.

    Selectors:
      label <label>              item label must equal label
      filter-text <text>         item filterText must equal text
      label-detail <detail>      item labelDetails.detail must equal detail
      detail-contains <text>     item detail must contain text
      index <n>                  choose the nth item after filters, default 0

    For accept inject, <code> may contain § to place the completion cursor
    before trailing text.  The marker is removed from the temporary content.

reset
    Discard any injected content and restore the server's view to the
    original file content.

log [<n>]
    Print the last <n> relevant lines from the server log (default 20).

help
    Print this message.

quit / exit / q
    End the session.

ASSERTION QUALIFIERS
--------------------
Append after the main command arguments to assert properties of the returned
completion list.  Multiple qualifiers may be combined.

  expect <label> [<label> ...]
      Each listed label must appear as a prefix of at least one returned item.
      Example: complete after "MongoClients." expect create createWithCustomClass

  min <n>
      At least <n> items must be returned.
      Example: complete after "settings." min 5

  max <n>
      At most <n> items may be returned.
      Example: complete after "." max 0

  filter <prefix>
      Every returned item's label must start with <prefix> (case-insensitive).
      Example: complete after "set" filter set

When all qualifiers pass the line starts with  [PASS].
When any qualifier fails the line starts with  [FAIL]  and the exit code is 1.
When there are no qualifiers the result is printed without a status badge.
"""

import json
import re
import shlex
import sys
import time
from pathlib import Path

try:
    import readline  # noqa: F401  — activates line-editing in interactive mode
except ImportError:
    pass

sys.path.insert(0, str(Path(__file__).parent))
from lsp import LatheClient, find_workspace_root

# ── constants ─────────────────────────────────────────────────────────────────

_SEP = "─" * 64

_KIND_NAMES = {
    1: "Text", 2: "Method", 3: "Function", 4: "Constructor",
    5: "Field", 6: "Variable", 7: "Class", 8: "Interface",
    9: "Module", 10: "Property", 11: "Unit", 12: "Value",
    13: "Enum", 14: "Keyword", 15: "Snippet", 16: "Color",
    17: "File", 18: "Reference", 19: "Folder", 20: "EnumMember",
    21: "Constant", 22: "Struct", 23: "Event", 24: "Operator",
    25: "TypeParameter",
}

_LOG_KEYWORDS = (
    "inject prefix", "sentinelCtx", "resolve receiver",
    "type-index", "typeRef", "proposals", "WARN", "ERROR", "completion",
)

_ASSERTION_KEYWORDS = frozenset(("expect", "min", "max", "filter"))
_ACCEPT_SELECTOR_KEYWORDS = frozenset((
    "label", "filter-text", "label-detail", "detail-contains", "index",
))


# ── assertion helpers ─────────────────────────────────────────────────────────

def _parse_assertions(args: list[str]) -> tuple[list[str], dict]:
    """
    Scan args left-to-right.  Once an assertion keyword is encountered every
    subsequent token is consumed as part of assertions.  Returns
    (pre_assertion_args, assertions).

    Assertion keywords: expect, min, max, filter.
    """
    # Find the first assertion keyword.
    split_at = len(args)
    for i, a in enumerate(args):
        if a.lower() in _ASSERTION_KEYWORDS:
            split_at = i
            break

    pre = args[:split_at]
    rest = args[split_at:]
    assertions: dict = {}

    i = 0
    while i < len(rest):
        kw = rest[i].lower()
        if kw == "expect":
            i += 1
            labels: list[str] = []
            while i < len(rest) and rest[i].lower() not in _ASSERTION_KEYWORDS:
                labels.append(rest[i])
                i += 1
            assertions["expect"] = labels
        elif kw == "min":
            i += 1
            assertions["min"] = int(rest[i])
            i += 1
        elif kw == "max":
            i += 1
            assertions["max"] = int(rest[i])
            i += 1
        elif kw == "filter":
            i += 1
            assertions["filter"] = rest[i]
            i += 1
        else:
            i += 1  # skip unknown token

    return pre, assertions


def _check_refs_assertions(refs: list[dict], assertions: dict) -> list[str]:
    """Return failure messages for ref-location assertions (empty → all pass).

    expect <substr>  — at least one ref URI must contain substr.
    min <n>          — at least n references required.
    max <n>          — at most n references allowed.
    """
    if not assertions:
        return []

    failures: list[str] = []
    paths = [ref.get("uri", "").removeprefix("file://") for ref in refs]

    for expected in assertions.get("expect", []):
        if not any(expected in p for p in paths):
            failures.append(
                f"expected a reference whose path contains {expected!r} — not found"
            )

    if "min" in assertions and len(refs) < assertions["min"]:
        failures.append(
            f"expected ≥{assertions['min']} reference(s), got {len(refs)}"
        )

    if "max" in assertions and len(refs) > assertions["max"]:
        failures.append(
            f"expected ≤{assertions['max']} reference(s), got {len(refs)}"
        )

    return failures


def _check_assertions(items: list[dict], assertions: dict) -> list[str]:
    """Return a list of failure messages (empty → all pass)."""
    if not assertions:
        return []

    failures: list[str] = []
    labels = [it.get("label", "") for it in items]

    for expected in assertions.get("expect", []):
        if not any(lbl.startswith(expected) for lbl in labels):
            failures.append(f"expected label starting with {expected!r} — not found")

    if "min" in assertions and len(items) < assertions["min"]:
        failures.append(f"expected ≥{assertions['min']} items, got {len(items)}")

    if "max" in assertions and len(items) > assertions["max"]:
        failures.append(f"expected ≤{assertions['max']} items, got {len(items)}")

    if "filter" in assertions:
        prefix = assertions["filter"].lower()
        bad = [lbl for lbl in labels if not lbl.lower().startswith(prefix)]
        if bad:
            failures.append(
                f"filter {assertions['filter']!r}: {len(bad)} item(s) do not match"
                f" — e.g. {bad[:3]}"
            )

    return failures


def _print_assertion_result(failures: list[str]) -> bool:
    """Print PASS/FAIL badge and return True if passed."""
    if failures:
        print("  [FAIL]")
        for f in failures:
            print(f"    ✗  {f}")
        return False
    print("  [PASS]")
    return True


def _offset_at(content: str, line: int, col: int) -> int:
    lines = content.splitlines(keepends=True)
    return sum(len(lines[i]) for i in range(line)) + col


def _position_at(content: str, offset: int) -> tuple[int, int]:
    before = content[:offset]
    line = before.count("\n")
    line_start = before.rfind("\n") + 1
    return line, offset - line_start


def _edit_range(edit: dict) -> dict:
    if "range" in edit:
        return edit["range"]
    if "replace" in edit:
        return edit["replace"]
    if "insert" in edit:
        return edit["insert"]
    raise ValueError(f"unsupported text edit shape: {edit}")


def _snippet_text_and_cursor(text: str) -> tuple[str, int | None]:
    cursor = None
    out = []
    index = 0
    pattern = re.compile(r"\$(\d+)|\$\{(\d+):([^}]*)\}")
    for match in pattern.finditer(text):
        out.append(text[index:match.start()])
        number = int(match.group(1) or match.group(2))
        default = match.group(3) or ""
        if cursor is None and number in (1, 0):
            cursor = sum(len(part) for part in out)
        out.append(default)
        index = match.end()
    out.append(text[index:])
    return "".join(out), cursor


def _apply_completion(content: str, item: dict) -> tuple[str, int]:
    text_edit = item.get("textEdit")
    if not text_edit:
        raise ValueError("selected completion item has no textEdit")

    primary_range = _edit_range(text_edit)
    new_text = text_edit.get(
        "newText",
        item.get("textEditText", item.get("insertText", item["label"])),
    )
    if item.get("insertTextFormat") == 2:
        applied_text, cursor_in_new_text = _snippet_text_and_cursor(new_text)
    else:
        applied_text = new_text
        cursor_in_new_text = len(applied_text)

    primary_start = _offset_at(
        content,
        primary_range["start"]["line"],
        primary_range["start"]["character"],
    )
    primary_end = _offset_at(
        content,
        primary_range["end"]["line"],
        primary_range["end"]["character"],
    )

    edits = []
    for edit in item.get("additionalTextEdits") or []:
        rng = _edit_range(edit)
        edits.append((
            _offset_at(content, rng["start"]["line"], rng["start"]["character"]),
            _offset_at(content, rng["end"]["line"], rng["end"]["character"]),
            edit.get("newText", ""),
            None,
        ))
    edits.append((primary_start, primary_end, applied_text, cursor_in_new_text))

    result = content
    cursor = None
    for start, end, replacement, cursor_in_edit in sorted(edits, key=lambda e: e[0], reverse=True):
        result = result[:start] + replacement + result[end:]
        if cursor_in_edit is not None:
            cursor = start + cursor_in_edit

    if cursor is None:
        cursor = primary_start + len(applied_text)
    return result, cursor


def _line_context(content: str, cursor: int, radius: int = 2) -> str:
    marked = content[:cursor] + "§" + content[cursor:]
    line, _ = _position_at(marked, cursor)
    lines = marked.splitlines()
    start = max(0, line - radius)
    end = min(len(lines), line + radius + 1)
    return "\n".join(f"{i + 1:5d}  {lines[i]}" for i in range(start, end))


# ── shell ─────────────────────────────────────────────────────────────────────

class ExploreShell:
    """REPL state for a single open-file session."""

    def __init__(self, file: Path, client: LatheClient) -> None:
        self._file = file
        self._client = client
        self._original: str = file.read_text()
        self._current: str = self._original
        self._version: int = 1
        self._injected: bool = False
        self.any_failure: bool = False  # set True if any assertion fails

    # ── command dispatch ──────────────────────────────────────────────────────

    def run_command(self, raw: str) -> bool:
        """Parse and execute one command. Returns False to signal exit."""
        raw = raw.strip()
        if not raw or raw.startswith("#"):
            return True
        try:
            parts = shlex.split(raw)
        except ValueError as exc:
            print(f"parse error: {exc}")
            return True

        cmd, args = parts[0].lower(), parts[1:]

        if cmd in ("quit", "exit", "q"):
            return False

        _DISPATCH = {
            "show":        self._cmd_show,
            "grep":        self._cmd_grep,
            "complete":    self._cmd_complete,
            "accept":      self._cmd_accept,
            "hover":       self._cmd_hover,
            "definition":  self._cmd_definition,
            "def":         self._cmd_definition,
            "refs":        self._cmd_refs,
            "diagnostics": self._cmd_diagnostics,
            "diag":        self._cmd_diagnostics,
            "inject":      self._cmd_inject,
            "reset":       self._cmd_reset,
            "log":         self._cmd_log,
            "help":        self._cmd_help,
        }

        handler = _DISPATCH.get(cmd)
        if handler is None:
            print(f"unknown command: {cmd!r}  (type 'help' for available commands)")
        else:
            try:
                handler(args)
            except (KeyboardInterrupt, EOFError):
                return False
            except Exception as exc:
                print(f"error: {exc}")

        return True

    # ── shared helpers ────────────────────────────────────────────────────────

    def _lines(self) -> list[str]:
        return self._current.splitlines()

    def _find_text(self, text: str) -> tuple[int, int] | None:
        """Return 0-based (line, col) of the first occurrence of text."""
        for i, ln in enumerate(self._lines()):
            idx = ln.find(text)
            if idx >= 0:
                return i, idx
        return None

    @staticmethod
    def _print_context(lines: list[str], center: int, radius: int = 4) -> None:
        start = max(0, center - radius)
        end = min(len(lines), center + radius + 1)
        for i in range(start, end):
            marker = ">>>" if i == center else "   "
            print(f"{marker} {i + 1:5d}  {lines[i]}")

    @staticmethod
    def _print_items(items: list[dict], limit: int = 30) -> None:
        if not items:
            print("  (no completions returned)")
            return
        print(f"  {len(items)} item(s):")
        for it in items[:limit]:
            label  = it.get("label", "?")
            detail = it.get("detail") or ""
            kind   = _KIND_NAMES.get(it.get("kind"), "")
            parts  = []
            if kind:
                parts.append(f"[{kind}]")
            if detail:
                parts.append(detail)
            suffix = "  " + "  ".join(parts) if parts else ""
            print(f"    {label}{suffix}")
        if len(items) > limit:
            print(f"    … {len(items) - limit} more")

    def _evaluate(self, items: list[dict], assertions: dict) -> None:
        """Print items and, if assertions were given, print PASS/FAIL."""
        self._print_items(items)
        if assertions:
            failures = _check_assertions(items, assertions)
            passed = _print_assertion_result(failures)
            if not passed:
                self.any_failure = True

    def _injected_content(self, code: str, target_0: int | None) -> tuple[str, int, int, str]:
        lines = self._original.splitlines()

        if target_0 is None:
            for i, ln in enumerate(lines):
                s = ln.strip()
                if s.endswith("{") and not s.startswith("//") and i + 1 < len(lines):
                    indent_str = " " * (len(ln) - len(ln.lstrip()) + 4)
                    target_0 = i + 1
                    break
            else:
                raise ValueError("could not locate a method body — use: inject <code> at <line>")
        else:
            indent_str = " " * (len(lines[target_0]) - len(lines[target_0].lstrip()))

        injected_line = code if code.startswith((" ", "\t")) else indent_str + code
        col = injected_line.index("§") if "§" in injected_line else len(injected_line)
        injected_line = injected_line.replace("§", "")
        new_lines = list(lines)
        new_lines.insert(target_0, injected_line)
        return "\n".join(new_lines), target_0, col, injected_line

    @staticmethod
    def _parse_accept_selector(args: list[str]) -> dict:
        selector = {"index": 0}
        i = 0
        while i < len(args):
            key = args[i].lower()
            if key not in _ACCEPT_SELECTOR_KEYWORDS:
                raise ValueError(f"unknown accept selector token: {args[i]!r}")
            if key == "index":
                if i + 1 >= len(args):
                    raise ValueError("index requires a value")
                selector["index"] = int(args[i + 1])
                i += 2
                continue
            if i + 1 >= len(args):
                raise ValueError(f"{key} requires a value")
            selector[key] = args[i + 1]
            i += 2
        return selector

    @staticmethod
    def _select_accept_item(items: list[dict], selector: dict) -> dict:
        matches = items
        if "label" in selector:
            matches = [item for item in matches if item.get("label") == selector["label"]]
        if "filter-text" in selector:
            matches = [
                item for item in matches if item.get("filterText") == selector["filter-text"]
            ]
        if "label-detail" in selector:
            matches = [
                item
                for item in matches
                if (item.get("labelDetails") or {}).get("detail") == selector["label-detail"]
            ]
        if "detail-contains" in selector:
            matches = [
                item
                for item in matches
                if selector["detail-contains"] in (item.get("detail") or "")
            ]

        index = selector["index"]
        if not matches:
            raise ValueError("no completion item matched the selection filters")
        if index >= len(matches):
            raise ValueError(f"index {index} out of range for {len(matches)} match(es)")
        return matches[index]

    # ── commands ──────────────────────────────────────────────────────────────

    def _cmd_show(self, args: list[str]) -> None:
        lines = self._lines()
        if args:
            try:
                center = int(args[0]) - 1   # 1-based → 0-based
            except ValueError:
                print(f"  expected a line number, got {args[0]!r}")
                return
            self._print_context(lines, center, radius=10)
        else:
            for i, ln in enumerate(lines):
                print(f"  {i + 1:5d}  {ln}")

    def _cmd_grep(self, args: list[str]) -> None:
        if not args:
            print("usage: grep <pattern>")
            return
        pattern = args[0]
        found = 0
        for i, ln in enumerate(self._lines()):
            if pattern in ln:
                print(f"  {i + 1:5d}  {ln}")
                found += 1
        if not found:
            print(f"  (no matches for {pattern!r})")

    def _cmd_complete(self, args: list[str]) -> None:
        if not args:
            print("usage: complete <line>:<col> [assertions]")
            print("       complete after <text> [assertions]")
            return

        pre, assertions = _parse_assertions(args)

        if pre and pre[0].lower() == "after":
            if len(pre) < 2:
                print("usage: complete after <text> [assertions]")
                return
            text = pre[1]
            pos = self._find_text(text)
            if pos is None:
                print(f"  text not found: {text!r}")
                return
            line, col = pos[0], pos[1] + len(text)
            print(f"  completing after {text!r}  →  {line}:{col}")
        elif pre:
            try:
                line, col = (int(x) for x in pre[0].split(":", 1))
            except (ValueError, TypeError):
                print(f"  expected line:col (0-based) or 'after <text>', got {pre[0]!r}")
                return
        else:
            print("usage: complete <line>:<col> [assertions]")
            print("       complete after <text> [assertions]")
            return

        print(_SEP)
        self._print_context(self._lines(), line)
        print(_SEP)

        try:
            items = self._client.completion(self._file, line, col)
        except TimeoutError:
            print("  TIMEOUT — server did not respond in time")
            return

        self._evaluate(items, assertions)

    def _cmd_accept(self, args: list[str]) -> None:
        if not args:
            print("usage: accept <line>:<col> <selector>")
            print("       accept after <text> <selector>")
            print("       accept inject <code> [at <line>] <selector>")
            return

        content = self._current
        selector_args: list[str]

        if args[0].lower() == "after":
            if len(args) < 3:
                print("usage: accept after <text> <selector>")
                return
            text = args[1]
            pos = self._find_text(text)
            if pos is None:
                print(f"  text not found: {text!r}")
                return
            line, col = pos[0], pos[1] + len(text)
            selector_args = args[2:]
            print(f"  accepting after {text!r}  →  {line}:{col}")
        elif args[0].lower() == "inject":
            if len(args) < 3:
                print("usage: accept inject <code> [at <line>] <selector>")
                return
            code = args[1]
            target_0: int | None = None
            selector_start = 2
            if len(args) >= 4 and args[2].lower() == "at":
                try:
                    target_0 = int(args[3]) - 1
                except ValueError:
                    print(f"  invalid line number: {args[3]!r}")
                    return
                selector_start = 4
            try:
                content, line, col, injected_line = self._injected_content(code, target_0)
            except ValueError as exc:
                print(f"  {exc}")
                return
            self._current = content
            self._version += 1
            self._injected = True
            self._client.change(self._file, content, self._version)
            time.sleep(0.05)
            selector_args = args[selector_start:]
            print(f"  injected at line {line + 1}:  {injected_line!r}")
            print(f"  accepting at {line}:{col}")
        elif ":" in args[0]:
            try:
                line, col = (int(x) for x in args[0].split(":", 1))
            except (ValueError, TypeError):
                print(f"  expected line:col (0-based), got {args[0]!r}")
                return
            selector_args = args[1:]
        else:
            print(f"  expected line:col, 'after <text>', or 'inject <code>', got {args[0]!r}")
            return

        try:
            selector = self._parse_accept_selector(selector_args)
        except ValueError as exc:
            print(f"  {exc}")
            return

        print(_SEP)
        self._print_context(content.splitlines(), line)
        print(_SEP)

        try:
            items = self._client.completion(self._file, line, col)
            item = self._select_accept_item(items, selector)
            accepted, cursor = _apply_completion(content, item)
        except TimeoutError:
            print("  TIMEOUT")
            return
        except ValueError as exc:
            print(f"  {exc}")
            return

        print(f"  completion site: {line}:{col}")
        print(f"  matched items: {len(items)} total")
        print("  selected item:")
        for ln in json.dumps(item, indent=2, sort_keys=True).splitlines():
            print(f"  {ln}")
        print("  accepted content around cursor:")
        print(_line_context(accepted, cursor))

    def _cmd_hover(self, args: list[str]) -> None:
        if not args:
            print("usage: hover <line>:<col>  OR  hover <text>")
            return

        if ":" in args[0]:
            try:
                line, col = (int(x) for x in args[0].split(":", 1))
            except (ValueError, TypeError):
                print(f"  expected line:col (0-based), got {args[0]!r}")
                return
        else:
            pos = self._find_text(args[0])
            if pos is None:
                print(f"  text not found: {args[0]!r}")
                return
            line, col = pos
            print(f"  found {args[0]!r} at {line}:{col}")

        try:
            result = self._client.hover(self._file, line, col)
        except TimeoutError:
            print("  TIMEOUT")
            return

        if result is None:
            print("  (no hover result)")
            return

        contents = result.get("contents", {})
        value = (
            contents.get("value", "")
            if isinstance(contents, dict)
            else str(contents)
        )
        for ln in value.strip().splitlines():
            print(f"  {ln}")

    def _cmd_definition(self, args: list[str]) -> None:
        if not args:
            print("usage: definition <line>:<col>")
            return
        try:
            line, col = (int(x) for x in args[0].split(":", 1))
        except (ValueError, TypeError):
            print(f"  expected line:col (0-based), got {args[0]!r}")
            return

        try:
            locs = self._client.definition(self._file, line, col)
        except TimeoutError:
            print("  TIMEOUT")
            return

        if not locs:
            print("  (no definition found)")
            return

        for loc in locs:
            uri   = loc.get("uri") or loc.get("targetUri", "?")
            r     = loc.get("range") or loc.get("targetSelectionRange", {})
            start = r.get("start", {})
            path  = uri.removeprefix("file://")
            ln    = start.get("line", "?")
            ch    = start.get("character", "?")
            print(f"  {path}:{ln + 1 if isinstance(ln, int) else ln}"
                  f":{ch + 1 if isinstance(ch, int) else ch}")

    def _cmd_refs(self, args: list[str]) -> None:
        if not args:
            print("usage: refs <line>:<col> [assertions]")
            print("       refs <text>        [assertions]")
            return

        pre, assertions = _parse_assertions(args)

        if not pre:
            print("usage: refs <line>:<col> [assertions]")
            print("       refs <text>        [assertions]")
            return

        if ":" in pre[0] and pre[0][0].isdigit():
            try:
                line, col = (int(x) for x in pre[0].split(":", 1))
            except (ValueError, TypeError):
                print(f"  expected line:col (0-based) or text, got {pre[0]!r}")
                return
        else:
            pos = self._find_text(pre[0])
            if pos is None:
                print(f"  text not found: {pre[0]!r}")
                return
            line, col = pos
            print(f"  found {pre[0]!r} at {line}:{col}")

        try:
            refs = self._client.references(self._file, line, col)
        except TimeoutError:
            print("  TIMEOUT")
            return

        if not refs:
            print("  (no references found)")
            if assertions:
                passed = _print_assertion_result(_check_refs_assertions([], assertions))
                if not passed:
                    self.any_failure = True
            return

        print(f"  {len(refs)} reference(s):")
        limit = len(refs) if assertions else 15
        for ref in refs[:limit]:
            uri   = ref.get("uri", "?")
            r     = ref.get("range", {})
            start = r.get("start", {})
            path  = uri.removeprefix("file://")
            ln    = start.get("line", "?")
            ch    = start.get("character", "?")
            print(f"    {path}:{ln + 1 if isinstance(ln, int) else ln}"
                  f":{ch + 1 if isinstance(ch, int) else ch}")
        if len(refs) > limit:
            print(f"    … {len(refs) - limit} more")

        if assertions:
            passed = _print_assertion_result(_check_refs_assertions(refs, assertions))
            if not passed:
                self.any_failure = True

    def _cmd_diagnostics(self, args: list[str]) -> None:
        try:
            diags = self._client.save(self._file)
        except TimeoutError:
            print("  TIMEOUT")
            return

        if not diags:
            print("  OK — no errors or warnings")
            return

        for d in diags:
            sev   = {1: "ERROR", 2: "WARN ", 3: "INFO "}.get(d["severity"], "?    ")
            start = d["range"]["start"]
            msg   = d["message"].split("\n")[0]
            print(f"  [{sev}] {start['line'] + 1}:{start['character'] + 1}  {msg}")

    def _cmd_inject(self, args: list[str]) -> None:
        if not args:
            print("usage: inject <code> [at <line>] [assertions]")
            return

        pre, assertions = _parse_assertions(args)

        code = pre[0] if pre else ""
        target_0: int | None = None

        if len(pre) >= 3 and pre[1].lower() == "at":
            try:
                target_0 = int(pre[2]) - 1   # 1-based → 0-based
            except ValueError:
                print(f"  invalid line number: {pre[2]!r}")
                return

        try:
            new_content, target_0, col, injected_line = self._injected_content(code, target_0)
        except ValueError as exc:
            print(f"  {exc}")
            return

        self._current = new_content
        self._version += 1
        self._injected = True
        self._client.change(self._file, new_content, self._version)
        time.sleep(0.05)

        print(f"  injected at line {target_0 + 1}:  {injected_line!r}")
        print(f"  completing at {target_0}:{col}")
        print(_SEP)

        try:
            items = self._client.completion(self._file, target_0, col)
        except TimeoutError:
            print("  TIMEOUT")
            return

        self._evaluate(items, assertions)

    def _cmd_reset(self, args: list[str]) -> None:
        if not self._injected:
            print("  nothing to reset — content is already original")
            return
        self._current = self._original
        self._version += 1
        self._injected = False
        self._client.change(self._file, self._original, self._version)
        print("  reset to original content")

    def _cmd_log(self, args: list[str]) -> None:
        n = int(args[0]) if args else 20
        relevant = [
            ln for ln in self._client.stderr_lines
            if any(k in ln for k in _LOG_KEYWORDS)
        ]
        recent = relevant[-n:]
        if not recent:
            print("  (no relevant server log entries)")
        else:
            for ln in recent:
                print(f"  {ln}")

    def _cmd_help(self, args: list[str]) -> None:
        print(__doc__)


# ── main ──────────────────────────────────────────────────────────────────────

def main() -> None:
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help"):
        print(__doc__)
        sys.exit(0)

    file = Path(sys.argv[1]).expanduser().resolve()
    if not file.exists():
        print(f"error: file not found: {file}", file=sys.stderr)
        sys.exit(1)

    inline_cmd = shlex.join(sys.argv[2:]) if len(sys.argv) > 2 else None

    try:
        workspace = find_workspace_root(file)
    except RuntimeError as exc:
        print(f"error: {exc}", file=sys.stderr)
        sys.exit(1)

    interactive = sys.stdin.isatty() and inline_cmd is None

    if interactive:
        print(f"lathe explore  {file.name}")
        print(f"workspace      {workspace}")
        print("type 'help' for commands, 'quit' to exit")
        print()

    with LatheClient.start(workspace, debug=True) as client:
        print(f"opening {file.name} ...", end=" ", flush=True)
        try:
            diags = client.open(file, timeout=40)
        except TimeoutError:
            print("TIMEOUT", file=sys.stderr)
            sys.exit(1)

        print("ok")
        if diags:
            sev_counts: dict[int, int] = {}
            for d in diags:
                sev_counts[d["severity"]] = sev_counts.get(d["severity"], 0) + 1
            parts = []
            if sev_counts.get(1):
                parts.append(f"{sev_counts[1]} error(s)")
            if sev_counts.get(2):
                parts.append(f"{sev_counts[2]} warning(s)")
            print(f"  diagnostics: {', '.join(parts)}")
        else:
            print("  diagnostics: none")

        if interactive:
            print()

        shell = ExploreShell(file, client)

        if inline_cmd is not None:
            shell.run_command(inline_cmd)
        elif interactive:
            while True:
                try:
                    raw = input("> ")
                except EOFError:
                    break
                if not shell.run_command(raw):
                    break
        else:
            for raw in sys.stdin:
                if not shell.run_command(raw):
                    break

    if shell.any_failure:
        sys.exit(1)


if __name__ == "__main__":
    main()
