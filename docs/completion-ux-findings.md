# Completion UX Findings

This document records what we know about Java IDE completion standards,
what the Lathe probe tool has revealed about the current implementation,
and the priority order for closing the gaps.

It complements [lathe-completion-presentation.md](lathe-completion-presentation.md),
which defines the target design.
This document records where we are now and what the highest-value next steps are.

---

## 1. LSP Protocol — Fields That Matter

| Field | Role |
|---|---|
| `label` | Shown in the dropdown. For methods should include the signature: `subList(int, int)`. |
| `filterText` | What the editor matches as the user types. Must be the bare name: `subList`. If absent, editor falls back to `label` — for methods, `subList(int, int)` then breaks prefix filtering. |
| `insertText` | What is inserted on commit. Fallback when `textEdit` is absent. |
| `textEdit` | Explicit `{range, newText}`. Range covers the typed prefix so the editor replaces rather than appends. Preferred over `insertText`. |
| `additionalTextEdits` | Applied on commit; used for import insertion. |
| `sortText` | Lexicographic sort key, controls ordering independent of label. |
| `detail` | Secondary line in the menu — return type, field type, or qualified class name. |
| `data` | Opaque key passed back in `completionItem/resolve` for lazy doc loading. |
| `insertTextFormat` | 1 = plain text (default), 2 = snippet (`$1`, `${1:hint}`, `$0` tab stops). |
| `isIncomplete` | On the `CompletionList`: tells the client to re-request as the user types more. |
| `commitCharacters` | Characters that auto-commit the item. `.` is natural for Java field/method access. |
| `preselect` | Hint to the client to pre-select this item; use for the single highest-relevance candidate. |

The completion response must fix `textEdit`, `insertText`, `filterText`, `sortText`,
and `additionalTextEdits` in the initial response.
`completionItem/resolve` may fill `documentation` and non-critical display fields only —
it must not change insertion, filtering, or ranking fields.

---

## 2. Insertion Behaviour — What Established IDEs Do

| Situation | Inserted text | Cursor lands |
|---|---|---|
| No-arg method `trim()` | `trim()` | after `)` — can type `.` to chain immediately |
| Method with params `substring(int)` | `substring(` | inside `(` — type the first argument |
| Method with snippets (JDT.LS) | `substring(${1:beginIndex})$0` | on param placeholder — Tab moves forward |
| Field or enum constant | `name` | after name |
| Local variable or parameter | `name` | after name |
| Type name | `ArrayList` | after name; import added via `additionalTextEdits` |
| Keyword | `return` | after keyword |

The key rule: **no-arg methods close themselves; methods with params leave the paren open.**
This makes chaining natural — after selecting `trim()` the user can immediately type `.length()`.

Snippet tab-stop insertion (`insertTextFormat: 2`) requires the client to advertise
`snippetSupport` in its capabilities.
When that flag is absent the server must fall back to the plain-text forms above.

---

## 3. Filtering and Ranking

- Editors match the typed prefix against `filterText` (or `label` if absent),
  case-insensitively in VS Code and IntelliJ; IntelliJ also does CamelCase fuzzy matching
  (`NPE` → `NullPointerException`).
- `filterText` must always be the bare symbol name — never the label with signature.
- `sortText` controls ordering within the filtered list.
  Without it, `getAllMembers` stream order puts `wait(long)`, `notify()`, `finalize()` etc.
  at the top of every member-access list, making it practically unusable.
- Expected-type ranking: in argument position, items whose type matches the expected
  parameter type should sort first. Lathe already implements this.
- Suggested `sortText` buckets for member access (numeric prefix + normalized name):

  | Prefix | Members |
  |---|---|
  | `1_` | Declared directly on the concrete receiver type |
  | `2_` | Inherited from interfaces and non-Object supertypes |
  | `3_` | Static helpers when receiver is a type name |
  | `7_` | Object utility methods (`toString`, `equals`, `hashCode`, `getClass`) |
  | `8_` | Deprecated members |
  | `9_` | Low-confidence or Object boilerplate (`wait`, `notify`, `finalize`) |

---

## 4. The `textEdit` Range Problem

Without `textEdit`, if the user has typed `toStr` and selects `toString()`,
some editors append instead of replacing, producing `toStrtoString()`.
The editor is specified to replace the prefix but not all clients do so reliably
without an explicit range.

The replacement range must cover exactly the typed prefix:

```text
range.start = line and character of the first character of the prefix
range.end   = cursor position (after the last typed character)
newText     = method name (or type name, variable name, etc.)
```

For an empty prefix (`list.§`) the range is zero-width at the cursor.

For completion before or inside an existing identifier (`accept(§connectionString)`),
`InsertReplaceEdit` gives the editor two ranges:

```text
insert  range: cursor..cursor           (pure insertion, no replacement)
replace range: tokenStart..tokenEnd     (replace the full existing identifier)
newText: "connectionString"
```

This matches standard Java IDE behaviour where completing onto an existing token
replaces it rather than duplicating it.

---

## 5. Import Auto-Insertion

When the user completes a type from the type index (e.g. `ArrayList` not yet imported),
the server should add the import statement via `additionalTextEdits`:

```text
textEdit.newText    = "ArrayList"
additionalTextEdits = [{ range: importInsertionPoint, newText: "import java.util.ArrayList;\n" }]
```

Without this, type-index completions leave a compile error and force the user to add
the import manually — which defeats the purpose of type-index completion.

Import placement rules to handle before shipping this:
- duplicate import guard
- same-package types (no import needed)
- `java.lang` types (no import needed)
- static imports
- JPMS visibility (only accessible packages)
- ordering within the import block (static vs. regular, alphabetical)

---

## 6. Related Features

**Signature help (`textDocument/signatureHelp`)**

After the user commits a method with params (cursor lands inside `(`), the editor
immediately sends `signatureHelp`. This shows the method signature with parameter
types and documentation as a tooltip while the user types each argument.
Without signature help, the parameter guidance disappears after commit.
Lathe does not yet implement `signatureHelp`.

**Commit characters**

Setting `commitCharacters: ["."]` on method items allows the editor to auto-commit
the completion when the user types `.` — so typing `str.tr.` selects `trim` and chains
in one gesture. This is optional and should be tested with `blink.cmp` before enabling,
as it can be disruptive if the prefix has multiple plausible completions.

---

## 7. Current Lathe State — Observed via `dev/probe.py`

The following was established by running `probe.py --suite ux --workspace helidon --logs`.

### What works

- Member access on direct field receivers resolves correctly and returns proposals.
- Cross-line method-call receivers (`builder()↵.applyConnectionString`) resolve correctly;
  30 items returned including the expected `applyConnectionString`.
- The sentinel parse and receiver resolution pipeline is fundamentally sound.
- Type-index is built (19 833 simple names from 202 shards) and queries execute in ~10 ms.

### What is broken or incomplete

**`filterText` missing on method items.**
`CompletionItemFactory.member()` sets `label = "toString()"` but does not set `filterText`.
Clients that filter against `label` will match `toString()` against `toStr` — which happens
to work — but some clients strip trailing parens differently.
Every method item must carry `filterText = "toString"`.

**`insertText` equals `label` for methods with params.**
`CompletionItemFactory.member()` sets `insertText = "name("` for methods with params —
that part is correct. But for no-arg methods, `insertText = "name()"` and `label = "name()"`,
so `label == insertText`. Clients that insert `label` when `insertText` is absent would
also insert correctly here, but the explicit `insertText = "name()"` should be kept.

**No `textEdit` on any item.**
Neither member access nor simple-name items carry a `textEdit`.
The typed prefix is not replaced by range — it is up to each client to infer the replacement.
This is the highest-impact gap.

**`equals`, `hashCode`, `toString`, `getClass` appear at the top of every member list.**
The Object boilerplate filter suppresses `wait`, `notify`, `notifyAll`, `finalize` but not
these four. They are not harmful to keep but they should rank at `7_` (after all domain
members), not at `0_` (the current default for inherited members).

**`toString()` and `getClass()` insert their full signature label.**
When `insertText = "toString()"` and no `textEdit` is present, some clients insert
the label string literally. This is technically correct for no-arg methods but once
`textEdit` is added the insert text should be `"toString()"` (for no-arg) or `"toString("` —
the current probe false-alarms on this because it checks `label == insertText with "("`,
but the real problem is the missing `textEdit` with range.

**Probe positions for probes 1, 3, 4 are off.**
`config.url` probe: col points at `config` (receiver), not `url` (member name) — the server
correctly parses `config` as a SIMPLE_NAME prefix, not a receiver. Probe column needs to
point to the character after the dot.
Same issue for `settingsBuilder.build` and `MongoClients.create`.

---

## 8. Gaps Ranked by User-Visible Impact

| Priority | Gap | Effect when missing |
|---|---|---|
| 1 | `textEdit` with explicit prefix range | Prefix duplicated on commit in some clients |
| 2 | `filterText` on all method items | Dropdown filter broken for signature labels |
| 3 | Object utility methods ranked to `7_` | `toString/equals/hashCode` top every list |
| 4 | Import auto-insertion via `additionalTextEdits` | Type-index completions leave compile errors |
| 5 | Snippet insertion for method params | No tab-stop navigation through arguments |
| 6 | `signatureHelp` after method commit | No parameter tooltip while typing arguments |
| 7 | `commitCharacters: ["."]` | Cannot chain method calls in a single gesture |
