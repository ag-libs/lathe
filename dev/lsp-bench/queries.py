# Positions, answer summaries and checks adapted from java-index's benchmark/bench.py
# (https://github.com/slgobinath/java-index), Copyright 2026 Gobinath Loganathan, Licensed under the
# Apache License, Version 2.0; changed to take the repository as a parameter and to key project
# locations by their path.
"""A query file's positions and checks: where each query points, how it is asked, and whether the
answer is right. A case is a dict: `name`, `method`, `at` (file, a substring of the line, the token,
its occurrence on the line) or `file` or `query`, and `expect`, a hand-written check. The checks and
the answer summaries follow java-index's harness (benchmark/bench.py), so spring-petclinic's results
compare with its published ones.
"""
import re
import urllib.parse
from pathlib import Path

CAPABILITY = {
    "textDocument/definition": "definitionProvider",
    "textDocument/typeDefinition": "typeDefinitionProvider",
    "textDocument/hover": "hoverProvider",
    "textDocument/references": "referencesProvider",
    "textDocument/documentHighlight": "documentHighlightProvider",
    "textDocument/implementation": "implementationProvider",
    "typeHierarchy/supertypes": "typeHierarchyProvider",
    "typeHierarchy/subtypes": "typeHierarchyProvider",
    "callHierarchy/incomingCalls": "callHierarchyProvider",
    "callHierarchy/outgoingCalls": "callHierarchyProvider",
    "textDocument/signatureHelp": "signatureHelpProvider",
    "textDocument/documentSymbol": "documentSymbolProvider",
    "workspace/symbol": "workspaceSymbolProvider",
    "textDocument/completion": "completionProvider",
    # Not a request: the diagnostics a server publishes after an edit, which every server does.
    "textDocument/publishDiagnostics": None,
}
# A completion's expected item must rank within this many, in the order Neovim shows them.
COMPLETION_TOP = 20

LOCATION_METHODS = ("textDocument/definition", "textDocument/typeDefinition", "textDocument/references",
                    "textDocument/implementation", "textDocument/documentHighlight")


def uri(repo, path):
    return (repo / path).as_uri()


def position(repo, at):
    """The LSP position one character into the token, on the first line holding the substring."""
    path, line_text, token = at[:3]
    occurrence = at[3] if len(at) > 3 else 0
    lines = (repo / path).read_text(encoding="utf-8").split("\n")
    matches = [i for i, line in enumerate(lines) if line_text in line]
    if not matches:
        raise SystemExit("case position not found: %r in %s" % (line_text, path))
    line = matches[0]
    col = lines[line].index(token, lines[line].index(line_text))
    for _ in range(occurrence):
        col = lines[line].index(token, col + 1)
    return {"line": line, "character": col + 1}


def opened_files(cases):
    return sorted({c["at"][0] for c in cases if "at" in c} | {c["file"] for c in cases if "file" in c}
                  | {c["edit"][0] for c in cases if "edit" in c})


def edited_text(repo, case):
    """The file with the case's line typed in after its anchor line, and the position at its end."""
    path, anchor, typed = case["edit"]
    lines = (repo / path).read_text(encoding="utf-8").split("\n")
    row = next(i for i, line in enumerate(lines) if anchor in line) + 1
    return "\n".join(lines[:row] + [typed] + lines[row:]), {"line": row, "character": len(typed)}


def change(client, repo, path, text, save=False):
    """Sends the whole document, as one change, at its next version; saving writes it to disk first,
    as an editor does, then sends didSave with its text. Returns the version."""
    versions = client.__dict__.setdefault("versions", {})
    versions[path] = versions.get(path, 1) + 1
    if save:
        (repo / path).write_text(text, encoding="utf-8")
    client.notify("textDocument/didChange", {
        "textDocument": {"uri": uri(repo, path), "version": versions[path]}, "contentChanges": [{"text": text}]})
    if save:
        client.notify("textDocument/didSave", {"textDocument": {"uri": uri(repo, path)}, "text": text})
    return versions[path]


def label_name(label):
    """The name an item completes, without jdtls's ' - package' or a signature."""
    match = re.match(r"[\w$]+", label.strip())
    return match.group(0) if match else label


def complete(client, repo, case, timeout):
    """Types the case's line, asks for completion at its end, and resolves the expected item when the
    server defers its details (jdtls does): the time a user waits for the item to apply."""
    path = case["edit"][0]
    text, pos = edited_text(repo, case)
    change(client, repo, path, text)
    try:
        result = client.request("textDocument/completion", {"textDocument": {"uri": uri(repo, path)}, "position": pos},
                                timeout=timeout)
        items = result.get("items", []) if isinstance(result, dict) else result or []
        items = sorted(items, key=lambda i: i.get("sortText") or i.get("label", ""))
        wanted = next((i for i in items if label_name(i.get("label", "")) == case["expect"]["completion"]), None)
        if wanted and "additionalTextEdits" not in wanted and client.__dict__.get("resolves_completion"):
            wanted = client.request("completionItem/resolve", wanted, timeout=timeout)
        return {"items": items, "resolved": wanted}
    finally:
        change(client, repo, path, (repo / path).read_text(encoding="utf-8"))


def ask(client, repo, case, timeout):
    method = case["method"]
    if method == "textDocument/completion":
        return complete(client, repo, case, timeout)
    if method == "workspace/symbol":
        return client.request(method, {"query": case["query"]}, timeout=timeout)
    if method == "textDocument/documentSymbol":
        return client.request(method, {"textDocument": {"uri": uri(repo, case["file"])}}, timeout=timeout)
    params = {"textDocument": {"uri": uri(repo, case["at"][0])}, "position": position(repo, case["at"])}
    if method.startswith(("typeHierarchy/", "callHierarchy/")):
        prepare = "textDocument/prepare%sHierarchy" % ("Type" if method.startswith("type") else "Call")
        items = client.request(prepare, params, timeout=timeout)
        return client.request(method, {"item": items[0]}, timeout=timeout) if items else []
    if method == "textDocument/references":
        params["context"] = {"includeDeclaration": False}
    return client.request(method, params, timeout=timeout)


def target(repo, loc):
    """A project location as its path, line and line text; anything else as its URI."""
    raw = loc.get("targetUri") or loc.get("uri")
    selection = loc.get("targetSelectionRange") or loc.get("range")
    decoded = urllib.parse.unquote(raw)
    prefix = repo.as_uri() + "/"
    if not decoded.startswith(prefix):
        return {"project": False, "uri": decoded}
    path = decoded[len(prefix):]
    line = selection["start"]["line"]
    text = (repo / path).read_text(encoding="utf-8").split("\n")[line].strip()
    return {"project": True, "path": path, "file": Path(path).name, "line": line + 1, "text": text}


def flatten_symbols(symbols):
    for s in symbols or []:
        yield s.get("name", "")
        yield from flatten_symbols(s.get("children"))


def hover_text(result):
    if not result:
        return ""
    contents = result.get("contents")
    parts = contents if isinstance(contents, list) else [contents]
    return "\n".join(p if isinstance(p, str) else p.get("value", "") for p in parts if p)


def summarize(repo, case, result):
    """An answer as {'names', 'locations', 'text'}, for its check and for comparing the servers."""
    method = case["method"]
    out = {"names": [], "locations": [], "text": ""}
    if result is None:
        return out
    if method in LOCATION_METHODS:
        for loc in result if isinstance(result, list) else [result]:
            if method == "textDocument/documentHighlight":
                start = loc["range"]["start"]
                out["locations"].append({"project": True, "path": case["at"][0], "line": start["line"] + 1,
                                         "column": start["character"] + 1})
                continue
            label = target(repo, loc)
            out["locations"].append(label)
            out["names"].append(label["file"][:-5] if label["project"] else label["uri"])
    elif method.startswith("typeHierarchy/"):
        out["names"] = [i.get("name", "") for i in result]
    elif method == "callHierarchy/incomingCalls":
        out["names"] = [c["from"].get("name", "") for c in result]
    elif method == "callHierarchy/outgoingCalls":
        out["names"] = [c["to"].get("name", "") for c in result]
    elif method == "textDocument/documentSymbol":
        out["names"] = list(flatten_symbols(result))
    elif method == "workspace/symbol":
        out["names"] = [s.get("name", "") for s in result]
    elif method == "textDocument/hover":
        out["text"] = hover_text(result)
    elif method == "textDocument/signatureHelp":
        out["names"] = [s.get("label", "") for s in result.get("signatures", [])]
    elif method == "textDocument/completion":
        out["names"] = [label_name(i.get("label", "")) for i in result["items"]][:COMPLETION_TOP]
        out["text"] = "\n".join(e.get("newText", "") for e in (result["resolved"] or {}).get("additionalTextEdits") or [])
    return out


def check(expect, summary):
    """True when a summarized answer meets the case's hand-written expectation."""
    names = summary["names"]
    locations = summary["locations"]
    if "locations" in expect:
        return any(loc.get("project") and loc["file"] == e.split(":")[0] and e.split(":", 1)[1] in loc["text"]
                   for e in expect["locations"] for loc in locations)
    if "library" in expect:
        return any(not loc.get("project") and expect["library"] in loc["uri"] for loc in locations)
    if "hover_contains" in expect:
        return all(w in summary["text"] for w in expect["hover_contains"])
    if "min_count" in expect:
        return len(locations) >= expect["min_count"]
    if "contains" in expect:
        return all(any(w == n or n.startswith(w + "(") or n.startswith(w + " ") or w == n.split(".")[-1]
                       for n in names) for w in expect["contains"])
    if "completion" in expect:
        return expect["completion"] in names and ("import" not in expect or expect["import"] in summary["text"])
    if "signature_contains" in expect:
        return any(expect["signature_contains"] in n for n in names)
    return bool(names or locations or summary["text"])


def location_keys(summary):
    """A set-valued answer's project locations as 'path:line', for comparing the servers."""
    return sorted({"%s:%d" % (loc["path"], loc["line"]) for loc in summary["locations"] if loc.get("project")})
