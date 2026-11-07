#!/usr/bin/env python3
"""Applies the pre-registered position rules (README.md, Queries) to a project's clone by text
search, and prints each rule's winner and runners-up, for writing the project's query file.

    python3 dev/lsp-bench/pick.py dropwizard

How the rules are applied, by text: candidates are declared in the main sources of the project's main
module (projects.py), and their uses are counted over all the project's Java sources, tests included,
as a reference search returns them:
- a method is declared by modifiers, a type that is not a keyword, its name, its parameters and `{` or `;`;
- the widely used method is the main module's method whose name is declared once in the whole
  project, so text search can find its call sites, with the most call sites (`name(`);
- the common name is the main-module method name spelled `name(` in the most files;
- the static utility method is the most-called (`Type.name(`) static method of a main-module class
  whose methods are all static;
- the interface is the main-module interface named in the most `implements` clauses, and its method
  the one declared in the most of those implementing files;
- the field is the main-module field whose getter (`getX()`/`isX()`, declared in the same file) is
  called in the most other files;
- the enum constant is the main-module constant spelled `case NAME` in the most other files.
Kinds without a rule of their own take positions around these: the base class is the main-module
class named in the most `extends` clauses (implementations, subtypes; a subclass for supertypes).
A use counts only in a file that can mean the candidate: its own file, or one naming the top-level
type of its file, since simple names collide (a logger's `error()`, every `Builder`, every `NONE`).
Text search miscounts what shares a name; the counts pick a position, the answers are checked by hand.
"""
import collections
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import projects as P  # noqa: E402
import servers as S  # noqa: E402

KEYWORDS = {"if", "for", "while", "switch", "catch", "return", "new", "throw", "synchronized", "super",
            "this", "else", "do", "try", "assert", "case", "yield", "instanceof"}
METHOD_DECL = re.compile(r"^[ \t]*((?:(?:public|protected|private|static|final|abstract|synchronized|native|"
                         r"default|strictfp)\s+)*)(?:<[^>\n]+>\s+)?([\w.$\[\]]+(?:<[^;{}()]*>)?(?:\[\])*)\s+(\w+)\s*"
                         r"\(([^;{}]*?)\)\s*(?:throws\s+[\w.,\s]+)?[{;]", re.M | re.S)
CALL = re.compile(r"(?<![\w$])(\w+)\s*\(")
TYPE_DECL = re.compile(r"^[ \t]*(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\s+)*"
                       r"(class|interface|enum|record)\s+(\w+)", re.M)
EXTENDS = re.compile(r"\bclass\s+\w+(?:<[^{]*?>)?\s+extends\s+(\w+)")
IMPLEMENTS = re.compile(r"\bimplements\s+([^{]+)\{")
FIELD_DECL = re.compile(r"^\s*(?:private|protected|public)?\s*(?:static\s+)?(?:final\s+)?[\w.<>\[\]?, ]+\s+(\w+)\s*(?:=[^;]*)?;",
                        re.M)
WORD = re.compile(r"[A-Za-z_$][\w$]*")
CASE = re.compile(r"\bcase\s+(?:\w+\.)?([A-Z][A-Z0-9_]+)\b")
ENUM_BODY = re.compile(r"\benum\s+\w+[^{]*\{([^;}]*)", re.S)


def java_sources(repo):
    return [p for p in repo.rglob("*.java")
            if "/src/" in p.as_posix() and not any(d in p.parts for d in ("target", "build", ".lathe"))]


def text(path):
    return path.read_text(encoding="utf-8", errors="replace")


def declarations(source):
    """(name, modifiers, line) of each method declared in a source."""
    found = []
    for m in METHOD_DECL.finditer(source):
        type_name, name = m.group(2), m.group(3)
        if type_name not in KEYWORDS and name not in KEYWORDS and not name[0].isupper():
            found.append((name, m.group(1), source.count("\n", 0, m.start(3)) + 1))
    return found


def declaring_type(source):
    found = TYPE_DECL.search(source)
    return found.group(2) if found else None


def report(title, counter, where, top=5):
    print("\n%s" % title)
    for name, count in counter.most_common(top):
        print("  %6d  %s  %s" % (count, name, where.get(name, "")))


def main():
    name = sys.argv[1]
    repo = S.repo_of(name)
    module = P.PROJECTS[name]["main_module"]
    files = sorted(java_sources(repo))
    sources = {p: text(p) for p in files}
    words = {p: set(WORD.findall(source)) for p, source in sources.items()}
    calls = {p: collections.Counter(m.group(1) for m in CALL.finditer(source)) for p, source in sources.items()}
    in_module = [p for p in files if p.is_relative_to(repo / module / "src" / "main" / "java")]
    print("main module: %s (%d main sources; %d Java sources in the project)" % (module, len(in_module), len(files)))

    def sees(path, decl_file):
        """Whether a file can mean what decl_file declares: it is that file or names its top-level type."""
        return path == decl_file or top_type[decl_file] in words[path]

    top_type = {p: declaring_type(sources[p]) for p in files}
    declared = collections.Counter()
    owner = {}
    for path in files:
        for method, _mods, line in declarations(sources[path]):
            declared[method] += 1
            if path in in_module:
                owner.setdefault(method, (path, line))

    def at(method):
        path, line = owner[method]
        return "%s:%d" % (path.relative_to(repo), line)

    widely = collections.Counter({m: sum(calls[p][m] for p in files if sees(p, owner[m][0])) - 1
                                  for m in owner if declared[m] == 1})
    report("widely used method (name declared once in the project; call sites):", widely,
           {m: at(m) for m in widely})
    spelled = collections.Counter({m: sum(1 for p in files if calls[p][m]) for m in owner})
    report("common name (files spelling it):", spelled, {m: at(m) for m in spelled})

    statics = {}
    for path in in_module:
        types = TYPE_DECL.findall(sources[path])
        decls = declarations(sources[path])
        if types and types[0][0] == "class" and decls and all("static" in mods for _, mods, _ in decls):
            for method, _mods, line in decls:
                statics["%s.%s" % (types[0][1], method)] = "%s:%d" % (path.relative_to(repo), line)
    static_calls = collections.Counter({q: sum(s.count(q + "(") for s in sources.values()) for q in statics})
    report("static utility method (Type.name( calls):", static_calls, statics)

    interfaces = {t[1]: p for p in in_module for t in TYPE_DECL.findall(sources[p]) if t[0] == "interface"}
    implementers = collections.defaultdict(set)
    for path, source in sources.items():
        for clause in IMPLEMENTS.findall(source):
            for iface in WORD.findall(clause):
                if iface in interfaces and sees(path, interfaces[iface]):
                    implementers[iface].add(path)
    ranked = collections.Counter({i: len(ps) for i, ps in implementers.items()})
    report("interface (implementing files):", ranked, {i: interfaces[i].relative_to(repo) for i in interfaces})
    if ranked:
        top = ranked.most_common(1)[0][0]
        own = {m for m, _mods, _l in declarations(sources[interfaces[top]])}
        overriding = collections.Counter({m: sum(1 for p in implementers[top]
                                                 if any(d == m for d, _mods, _l in declarations(sources[p])))
                                          for m in own})
        report("  its method (implementing files declaring it):", overriding, {})

    classes = {t[1]: p for p in in_module for t in TYPE_DECL.findall(sources[p]) if t[0] == "class"}
    subclasses = collections.Counter()
    for path, source in sources.items():
        for base in EXTENDS.findall(source):
            if base in classes and sees(path, classes[base]):
                subclasses[base] += 1
    report("base class (subclass declarations):", subclasses, {c: classes[c].relative_to(repo) for c in classes})

    getters = collections.Counter()
    getter_where = {}
    for path in in_module:
        fields = set(FIELD_DECL.findall(sources[path]))
        for method, _mods, line in declarations(sources[path]):
            for prefix in ("get", "is"):
                field = method[len(prefix):][:1].lower() + method[len(prefix) + 1:]
                if method.startswith(prefix) and len(method) > len(prefix) and field in fields:
                    key = "%s.%s (%s)" % (top_type[path], field, method)
                    getters[key] = sum(1 for p in files if p != path and calls[p][method] and sees(p, path))
                    getter_where[key] = "%s:%d" % (path.relative_to(repo), line)
    report("field through a getter (other files calling it):", getters, getter_where)

    constants = {}
    for path in in_module:
        for body in ENUM_BODY.findall(sources[path]):
            for constant in re.findall(r"^\s*([A-Z][A-Z0-9_]+)\b", body, re.M):
                constants.setdefault("%s.%s" % (top_type[path], constant), path)
    labels = collections.Counter()
    for path, source in sources.items():
        found = set(CASE.findall(source))
        for key, decl_file in constants.items():
            if key.rsplit(".", 1)[1] in found and path != decl_file and sees(path, decl_file):
                labels[key] += 1
    report("enum constant as a switch label (other files):", labels,
           {c: constants[c].relative_to(repo) for c in constants})


if __name__ == "__main__":
    main()
