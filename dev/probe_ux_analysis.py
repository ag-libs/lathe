#!/usr/bin/env python3
"""
Simulate a developer editing three real-world files and probe completion at
positions that represent common Java coding patterns. Designed to surface
conceptual issues in the completion engine.

Files:
  - dropwizard AbstractServerFactory.java   (production server config code)
  - dropwizard ConstraintViolationExceptionMapperTest.java (fluent test assertions)
  - helidon MongoDbClient.java              (builder chains, static calls)

Usage:
    python3 dev/probe_ux_analysis.py
"""
import sys
sys.path.insert(0, str(__import__("pathlib").Path(__file__).parent))
from lsp import LatheClient, find_workspace_root
from pathlib import Path

D = "/home/ag-libs/git/dropwizard"
H = "/home/ag-libs/git/helidon"

ASF  = f"{D}/dropwizard-core/src/main/java/io/dropwizard/core/server/AbstractServerFactory.java"
CVT  = f"{D}/dropwizard-jersey/src/test/java/io/dropwizard/jersey/validation/ConstraintViolationExceptionMapperTest.java"
MONGO = f"{H}/dbclient/mongodb/src/main/java/io/helidon/dbclient/mongodb/MongoDbClient.java"

# Each probe: (file, line_0based, col_0based, description, expected_labels_hint)
# Lines/cols point to the START of the token being completed (simulates cursor right before typing).
PROBES = [
    # ── AbstractServerFactory: chained method call on single line ─────────────
    # handler.getServletContext().setAttribute(...)
    # Completing 'getServletContext' — single-hop member access on known field type
    (ASF, 577, 16, "ASF: handler.getServletContext — simple field member access", ["getServletContext"]),

    # ── AbstractServerFactory: two-hop chain on one line ─────────────────────
    # handler.getServletContext().setAttribute(...)  — cursor at 'setAttribute'
    # Receiver is a method call: getServletContext(), type = ServletContext
    (ASF, 577, 31, "ASF: getServletContext().setAttribute — method-call receiver", ["setAttribute"]),

    # ── AbstractServerFactory: cross-line chain, continuation on next line ────
    # handler.addFilter(AllowedMethodsFilter.class, "/*", EnumSet.of(...))
    #         .setInitParameter(...)    ← cursor here on the next line
    # Receiver spans the whole previous line; dotOffset crosses a newline.
    (ASF, 583, 17, "ASF: addFilter(...)\n.setInitParameter — cross-line chained call", ["setInitParameter"]),

    # ── AbstractServerFactory: cross-line simple-identifier receiver ──────────
    # final String banner = bufferedReader   ← receiver identifier, line N
    #     .lines()                           ← dot on line N+1, cursor at 'lines'
    # TypeResolver must scan for local 'bufferedReader' across the line gap.
    (ASF, 727, 25, "ASF: bufferedReader↵.lines — cross-line simple-name receiver", ["lines", "readLine", "close"]),

    # ── AbstractServerFactory: new-object then field chain ───────────────────
    # instrumented.setServer(server)  — InstrumentedEE10Handler field
    (ASF, 623, 21, "ASF: instrumented.setServer — new-object result member access", ["setServer", "setHandler"]),

    # ── AbstractServerFactory: constructor type in method body ────────────────
    # requestLog = new LogbackAccessRequestLogFactory();
    # Type-index should suggest the type name in 'new' expression position.
    (ASF, 322, 29, "ASF: new LogbackAccessR... — constructor type from type index", ["LogbackAccessRequestLogFactory"]),

    # ── Test file: fluent assertion on getStatus() return ────────────────────
    # assertThat(response.getStatus()).isEqualTo(422)
    # Receiver = MethodInvocationTree wrapping another call; return type = AbstractIntegerAssert
    (CVT, 67, 40, "CVT: assertThat(response.getStatus()).isEqualTo — nested call receiver", ["isEqualTo", "isNotEqualTo", "isGreaterThan"]),

    # ── Test file: fluent assertion on readEntity() ───────────────────────────
    # assertThat(response.readEntity(String.class)).isEqualTo(...)
    # Receiver contains type argument String.class; return type = AbstractStringAssert
    (CVT, 68, 53, "CVT: assertThat(response.readEntity(String.class)).isEqualTo — generic-arg receiver", ["isEqualTo", "contains", "startsWith"]),

    # ── Test file: cross-line request chain ──────────────────────────────────
    # target("/valid/foo").request(MediaType.APPLICATION_JSON)
    #         .post(Entity.entity(...))   ← cursor at 'post'
    (CVT, 66, 17, "CVT: target(...).request(...)↵.post — multi-hop cross-line chain", ["post", "get", "put", "delete"]),

    # ── MongoDbClient: method call on parameter field ─────────────────────────
    # new ConnectionString(config.url())
    # config is a MongoDbClientConfig param; url() is a method on it.
    (MONGO, 54, 71, "MONGO: config.url — member of method parameter type", ["url", "username", "password", "credDb"]),

    # ── MongoDbClient: cross-line builder chain ───────────────────────────────
    # MongoClientSettings.Builder settingsBuilder = MongoClientSettings.builder()
    #         .applyConnectionString(connectionString);   ← cursor at 'applyConnectionString'
    # Receiver = MethodInvocationTree (builder()); dotOffset crosses newline.
    (MONGO, 57, 17, "MONGO: MongoClientSettings.builder()↵.applyConnection — cross-line builder", ["applyConnectionString", "credential", "build"]),

    # ── MongoDbClient: method call on static factory result ──────────────────
    # MongoClients.create(settingsBuilder.build())
    # settingsBuilder.build() result used as argument; probing settingsBuilder.build
    (MONGO, 71, 57, "MONGO: settingsBuilder.build — builder result in static call arg", ["build"]),

    # ── MongoDbClient: static class method ───────────────────────────────────
    # MongoClients.create(...)  — static call on utility class
    (MONGO, 71, 22, "MONGO: MongoClients.create — static factory method", ["create"]),
]


def col_label(items: list[dict]) -> list[str]:
    return [i.get("label", "") for i in items]


def check(items: list[dict], expected: list[str]) -> str:
    labels = col_label(items)
    found = [e for e in expected if any(l.startswith(e) for l in labels)]
    missing = [e for e in expected if e not in found]
    return ("PASS" if not missing else f"MISS {missing}")


def kind_name(k: int | None) -> str:
    kinds = {1:"Text",2:"Method",3:"Function",4:"Constructor",5:"Field",
             6:"Variable",7:"Module",8:"Property",9:"Unit",10:"Value",
             11:"Enum",12:"Keyword",13:"Snippet",14:"Color",15:"File",
             16:"Reference",17:"Folder",18:"EnumMember",19:"Constant",
             20:"Struct",21:"Event",22:"Operator",23:"TypeParameter"}
    return kinds.get(k, f"?{k}") if k else "—"


def item_summary(item: dict) -> str:
    label   = item.get("label", "?")
    detail  = item.get("detail") or ""
    kind    = kind_name(item.get("kind"))
    insert  = item.get("insertText") or ""
    te      = item.get("textEdit") or {}
    te_text = te.get("newText", "") if isinstance(te, dict) else ""
    sort    = item.get("sortText") or ""
    filt    = item.get("filterText") or ""
    insert_effective = te_text or insert or label
    issues = []
    # Detect label=signature but insert=signature (should be just name)
    if "(" in label and insert_effective == label:
        issues.append("inserts-signature")
    if not te and not insert:
        issues.append("no-textEdit-no-insertText")
    return f"  [{kind}] {label!r:40s} detail={detail!r:30s} insert={insert_effective!r} {' '.join(issues)}"


def main():
    by_workspace: dict[str, list] = {}
    for probe in PROBES:
        path = probe[0]
        p = Path(path)
        if not p.exists():
            print(f"[skip] not found: {path}", file=sys.stderr)
            continue
        root = str(find_workspace_root(p))
        by_workspace.setdefault(root, []).append(probe)

    all_results = []
    for workspace, probes in by_workspace.items():
        print(f"\nworkspace: {workspace}", flush=True)
        files_to_open = list(dict.fromkeys(p[0] for p in probes))
        with LatheClient.start(workspace, debug=True) as c:
            opened = set()
            for path in files_to_open:
                try:
                    c.open(path, timeout=40)
                    opened.add(path)
                    print(f"  opened {Path(path).name}", flush=True)
                except Exception as e:
                    print(f"  [open-fail] {Path(path).name}: {e}", flush=True)

            for path, line, col, desc, expected in probes:
                if path not in opened:
                    all_results.append((desc, None, None, "open-failed"))
                    continue
                try:
                    import time
                    t0 = time.perf_counter()
                    items = c.completion(path, line, col)
                    ms = (time.perf_counter() - t0) * 1000
                    all_results.append((desc, items, expected, f"ok {ms:.0f}ms"))
                except TimeoutError:
                    all_results.append((desc, None, expected, "timeout"))
                except RuntimeError as e:
                    all_results.append((desc, None, expected, f"error: {e}"))

    print("\n" + "=" * 90)
    print("COMPLETION UX ANALYSIS")
    print("=" * 90)

    total_pass = 0
    total_fail = 0
    all_issues: list[str] = []

    for desc, items, expected, status in all_results:
        if items is None:
            print(f"\n[{status}] {desc}")
            total_fail += 1
            continue

        verdict = check(items, expected) if expected else "—"
        print(f"\n{'[PASS]' if verdict == 'PASS' else '[FAIL]'} {desc}")
        print(f"  status={status}  count={len(items)}  expected={expected}  verdict={verdict}")

        # Show top-8 items with issue detection
        for item in items[:8]:
            line_s = item_summary(item)
            print(line_s)
            if "inserts-signature" in line_s:
                all_issues.append(f"{desc}: item {item.get('label')!r} inserts its signature label")
            if "no-textEdit-no-insertText" in line_s:
                all_issues.append(f"{desc}: item {item.get('label')!r} has no textEdit and no insertText")
        if len(items) > 8:
            print(f"  ... and {len(items)-8} more")

        if verdict == "PASS":
            total_pass += 1
        else:
            total_fail += 1

    print("\n" + "=" * 90)
    print(f"Results: {total_pass} pass, {total_fail} fail")

    if all_issues:
        print(f"\nPresentation issues found ({len(all_issues)}):")
        for issue in all_issues:
            print(f"  - {issue}")
    else:
        print("\nNo presentation issues detected.")


if __name__ == "__main__":
    main()
