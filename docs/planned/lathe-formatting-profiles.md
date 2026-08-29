# Lathe — Formatting and Indentation Profiles

## Status

Planned.
This design changes Lathe's default formatting behavior from "Google Java Format on save" to an explicit,
project-sensitive choice.

The goal is to avoid rewriting projects that do not use Google Java Format while keeping Lathe's Java indentation useful
during normal editing.

This design targets **Neovim 0.12+**. The `editor_config` profile relies on Neovim's built-in
EditorConfig support (enabled by default since 0.9), so Lathe never parses `.editorconfig` itself.

## Problem

Lathe currently exposes server-side document formatting unconditionally and the Neovim plugin formats on save by
default.
The formatter is Google Java Format (`google-java-format`), which is intentionally opinionated:
2-space block indentation, 4-space continuation indentation, 100-column wrapping, import fixing, and broad
source rewrites.

That default is too strong for many Java projects.
Two representative Lathe validation projects use different style signals:

- Dropwizard has a repository `.editorconfig` that sets spaces and `indent_size = 4`.
- Helidon uses Maven Checkstyle with a Sun-convention-derived configuration, including no tabs, `tabWidth = 4`,
  `LineLength max = 130`, and project-specific import-order rules.

Neither project declares Google Java Format as its formatter contract.
Formatting those codebases with GJF on save can create noisy, surprising rewrites.

At the same time, indentation is still valuable while editing incomplete Java.
The current Neovim indentation plugin is local, fast, and tolerant of unparseable buffers, but its constants are
Google-shaped (`BLOCK_INDENT = 2`, `CONTINUATION_INDENT = 4`).
Indentation should therefore be paired with the selected formatting/style profile rather than always assuming GJF.

## Goals

- Make full-document Google Java Format opt-in.
- Disable Lathe format-on-save by default.
- Keep Java indentation enabled by default.
- Support two initial indentation profiles:
  `editor_config` and `google`.
- Use `.editorconfig` as the default source for basic indentation settings when available.
- Fall back to conservative Java defaults when no project indentation metadata exists.
- Advertise server formatting capability only when Google formatting is explicitly enabled.
- Preserve a simple way to opt into today's behavior.

## Non-goals

- Implement a general Java formatter.
- Parse arbitrary Checkstyle indentation rules in the first slice.
- Run project-specific external formatters.
- Implement `textDocument/onTypeFormatting` in the first slice (deferred — see "Future Work — Range-Aware Formatting").
- Fix range formatting in the first slice (deferred — see "Future Work — Range-Aware Formatting").
- Dynamically re-register formatting capabilities after initialization.
- Parse `.editorconfig` in Lathe — Neovim's built-in EditorConfig handles it (see `editor_config` profile).

## User Configuration

Neovim setup should accept:

```lua
require("lathe").setup({
  indent_style = "editor_config",
  formatter = nil,
  format_on_save = false,
})
```

Supported values:

```lua
indent_style = "editor_config" | "google"
formatter = nil | "google"
format_on_save = true | false
continuation_indent = nil | <number>
```

Defaults:

```lua
indent_style = "editor_config"
formatter = nil
format_on_save = false
continuation_indent = nil
```

`continuation_indent` overrides the continuation width for both profiles.
When `nil`, the width is derived as twice the block indent (see "Continuation indent is a heuristic" below).
When set to a number, that value is used verbatim as the continuation width in display columns.

Users who want the current Google-format-on-save workflow can configure:

```lua
require("lathe").setup({
  indent_style = "google",
  formatter = "google",
  format_on_save = true,
})
```

`format_on_save = true` without `formatter = "google"` should not install a formatting autocmd.
The plugin may warn once or silently ignore it; silently ignoring keeps setup quiet.

## Indentation Profiles

### `editor_config`

This is the default.

Lathe does **not** parse `.editorconfig` itself. Neovim 0.12+ ships built-in EditorConfig support
that is enabled by default (`vim.g.editorconfig = true`), and it already does everything a
hand-rolled parser would: it walks upward from the buffer path, honours `root = true`, matches
sections (including brace-list globs like `[*.{java,kt}]`) with correct EditorConfig precedence, and
maps the resolved properties onto buffer-local options:

- `indent_style` → `expandtab` (`space` sets it, `tab` clears it);
- `indent_size` → `shiftwidth` and `softtabstop`;
- `tab_width` → `tabstop`.

The `editor_config` profile therefore **consumes the buffer-local options Neovim has already
resolved** rather than reading any file. Because EditorConfig resolution is per-buffer and
path-sensitive, these values are read per buffer, not cached once at setup — two buffers in the same
session under different `.editorconfig` roots resolve independently.

#### Resolving indentation for a Java buffer

Read the effective buffer-local options and derive Lathe's indentation:

- spaces (`expandtab` set):
  block indent is the effective `shiftwidth` (falling back to `tabstop` when `shiftwidth = 0`, per
  Vim's own rule); continuation indent per "Continuation indent is a heuristic".
- tabs (`noexpandtab`):
  block indent is one tab; `tabstop` supplies the display width. Continuation indent follows the
  heuristic below, expressed in display columns.
- no resolved width (native editorconfig disabled, no matching `.editorconfig`, or the option is
  unset/zero with no tab fallback):
  fall back to block indent `4`, continuation indent `8`.

Dropwizard's `.editorconfig` (`indent_size = 4`) therefore surfaces as `shiftwidth = 4`, giving
4-space block indentation and (by default) 8-space continuation indentation — with no Lathe parsing
involved.

#### Dependency on native EditorConfig

This profile relies on Neovim's built-in EditorConfig being active. It is on by default; a user who
sets `vim.g.editorconfig = false` (or `vim.b.editorconfig = false`) opts out, and the fallback above
then applies. Lathe does not attempt to re-enable or replace it — there is a single source of truth
for `.editorconfig`, which avoids the two systems fighting over the same buffer options.

#### Continuation indent is a heuristic

EditorConfig has no continuation-indent concept, so Neovim resolves nothing for it. Deriving
continuation indent as `2 × block` is a **heuristic** borrowed from the Google Java Format ratio
(2→4). It is not implied by any `.editorconfig` key and will not match every project's wrapped-line
convention (many 4-space Java styles wrap at 4, not 8).

The `continuation_indent` option (see "User Configuration") lets a user pin the continuation width
explicitly. When it is set, it overrides the `2 × block` derivation for both profiles. When it is
`nil`, the `2 × block` default applies.

This profile is indentation-only.
It does not imply any full-document formatter.

### `google`

This preserves the existing Lathe Neovim indenter behavior:

- spaces;
- `shiftwidth = 2`;
- `softtabstop = 2`;
- `tabstop = 2`;
- block indent `2`;
- continuation indent `4`.

This profile pairs naturally with `formatter = "google"`, but users may still select Google indentation without
enabling full-document formatting.

A non-`nil` `continuation_indent` overrides the `4` continuation width here too.

## Neovim Plugin Changes

`lathe.lua` should own user options and pass indentation settings into `lathe.indent`.

Current behavior:

- `format_on_save` defaults to true.
- `ftplugin/java.lua` forces `shiftwidth = 2`, `softtabstop = 2`, and `tabstop = 2`.
- `lathe.indent` has hardcoded Google-shaped constants.

Planned behavior:

- `format_on_save` defaults to false.
- `formatter` defaults to nil.
- `lathe.indent` should expose a setup/config function that records the selected profile and the
  optional `continuation_indent`. Block/continuation widths for `editor_config` are read from
  buffer-local options per buffer (see below), not stored as module constants.
- For the `editor_config` profile, the plugin **does not** set `expandtab`, `shiftwidth`,
  `softtabstop`, or `tabstop` — Neovim's built-in EditorConfig owns them. `ftplugin/java.lua` must
  stop hardcoding `shiftwidth = 2` / `softtabstop = 2` / `tabstop = 2`, because those values run at
  `FileType` time and would clobber the EditorConfig-resolved options.
- For the `google` profile, the plugin sets buffer-local `expandtab`, `shiftwidth = 2`,
  `softtabstop = 2`, and `tabstop = 2` explicitly.
- The `indentexpr` wiring in `after/indent/java.lua` stays; `lathe.indent` reads the resolved
  buffer-local options at indent time, so setup ordering relative to EditorConfig does not matter.
- The plugin should install the save-format autocmd only when:

```lua
opts.formatter == "google" and opts.format_on_save == true
```

The setup function should also send server initialization options through the existing
`vim.lsp.config('lathe', { ... })` call (0.11+ config API, already used by the plugin):

```lua
vim.lsp.config('lathe', {
  -- existing cmd/filetypes/root_dir/capabilities ...
  init_options = {
    lathe = {
      formatter = opts.formatter,
    },
  },
})
```

This is new wiring: the plugin does not send `init_options` today.

## Server Changes

The server should parse initialization options:

```json
{
  "lathe": {
    "formatter": "google"
  }
}
```

Capability behavior:

- `formatter = "google"`:
  advertise `documentFormattingProvider = true`.
- otherwise:
  do not advertise `documentFormattingProvider`.

The formatting handler should still defensively return no edits when formatting is disabled.
This protects clients that send formatting requests despite the advertised capabilities.

The `rangeFormatting` and `onTypeFormatting` handlers must apply the same defensive gating: their
capabilities stay unadvertised and the handlers return no edits regardless of profile, so a client
that calls them anyway does not trigger a whole-document rewrite (see "Future Work — Range-Aware
Formatting").

`JavaFormatter` can remain unchanged in the first slice.
It remains the implementation behind `formatter = "google"`.

## On-Demand Formatting vs Format-On-Save

Lathe formatting is fundamentally an on-demand LSP request:
`textDocument/formatting`.

Format-on-save is only a Neovim plugin autocmd that sends that same request from `BufWritePre`.
The new config should make this explicit:

- `formatter = "google"` enables on-demand Lathe formatting.
- `format_on_save = true` additionally wires save-time invocation.

This lets a user manually format with Google Java Format without forcing every save to rewrite the file.

## Checkstyle Relationship

Checkstyle is not a formatter.
Helidon-style projects can use Checkstyle to validate many formatting and style rules, but Lathe cannot turn a
Checkstyle XML file into a complete formatting engine.

The first slice should not parse Checkstyle.
Future work may infer basic indentation hints from Checkstyle where the mapping is obvious, such as tab width or
indentation properties, but this should remain a separate design.

## Future Work — Range-Aware Formatting

This is deferred feature work to implement **once this formatting design has landed**; it is not part
of the first slice. It absorbs the findings previously tracked as gaps EG-029 and EG-028 (now
retired as standalone gaps).

### Current behaviour (the finding)

`textDocument/rangeFormatting` and `textDocument/onTypeFormatting` have handlers today, but their
capabilities are **not advertised** (only `documentFormattingProvider` is), so a spec-compliant
client never invokes them — the defect is dormant. If a client calls `rangeFormatting` anyway, it
delegates to the same whole-document path as `formatting`
(`JavaFormatter.format` → `Formatter().formatSourceAndFixImports(content)`): it ignores the request's
range and reformats — and reorders and removes imports across — the entire document. `onTypeFormatting`
is a stub returning no edits.

### Near-term (this design's slice)

Keep both capabilities unadvertised and make the handlers return no edits regardless of profile, as
noted under Server Changes. This neutralises the dormant range-format hazard for opt-in
`formatter = "google"` users without implementing anything new.

### The feature (after this design lands)

- Add a range path in `JavaFormatter` using GJF `Formatter.formatSource(text, ranges)`, deriving the
  character range(s) from the request's LSP range and emitting only the resulting in-range edits (no
  import fixing, which is inherently whole-file). Keep the whole-document path for `formatting`.
- Advertise `documentRangeFormattingProvider` only when `formatter = "google"`, alongside
  `documentFormattingProvider`.
- This range-scoped path is the prerequisite for conservative on-type formatting (below).

### On-type formatting (also deferred; absorbs former gap EG-028)

`textDocument/onTypeFormatting` is a stub returning no edits, and its capability is not registered,
so no client invokes it. If pursued, it can only be a **partial** improvement: GJF parses the whole
compilation unit and throws on unparseable input, and the most useful trigger — newline inside a
wrapped expression or record header — fires exactly when the buffer is not parseable, so a
GJF-backed handler returns nothing there. The realistic scope is triggers that *complete* a parseable
file (`}`, `;`): once the file parses, run the range-scoped path above over the touched lines and
return conservative brace/statement edits. The CLAUDE.md "no ad hoc Java parsing" rule rules out a
hand-rolled indentation model.

Priority is low and editor-dependent. In Neovim, error-tolerant client-side indentation (tree-sitter,
plus the indentation profiles above) already covers live typing, so a server-side handler adds
little. On-type formatting is mainly relevant to a VS Code integration, which is a later release, so
it is not a Neovim focus and stays deferred behind both this design and the range-aware path.

## Tests

Neovim tests. Because Lathe consumes buffer-local options rather than parsing `.editorconfig`, the
`editor_config` tests set the resolved options (`expandtab`/`shiftwidth`/`tabstop`) to stand in for
what native EditorConfig produces, rather than asserting on file parsing:

- default setup uses `editor_config`.
- with `expandtab` and `shiftwidth = 4`, block indent is 4 and continuation is 8.
- with `noexpandtab` and `tabstop = 4`, indentation uses a tab and 4-column display width.
- with `shiftwidth = 0`, block indent falls back to `tabstop` (Vim's rule).
- no resolved width (native editorconfig disabled / options unset) falls back to 4-space block,
  8-space continuation.
- the `editor_config` profile does not overwrite EditorConfig-resolved `shiftwidth`/`tabstop`.
- `continuation_indent = N` overrides the derived width in both `editor_config` and `google`.
- `indent_style = "google"` sets `expandtab` and 2/4 behavior.
- format-on-save autocmd is not installed by default.
- format-on-save autocmd is installed only with `formatter = "google"` and `format_on_save = true`.

Server tests:

- default initialization does not advertise `documentFormattingProvider`.
- initialization with `formatter = "google"` advertises `documentFormattingProvider`.
- formatting handler returns empty edits when formatting is disabled.
- formatting handler delegates to `JavaFormatter` when formatting is enabled.

Existing formatting tests for `JavaFormatter` should stay unchanged.

## Migration

Current setup:

```lua
require("lathe").setup()
```

New default behavior:

- no full-document formatting;
- no format-on-save;
- indentation follows `.editorconfig` when present, otherwise 4-space Java defaults.

To keep the old behavior:

```lua
require("lathe").setup({
  indent_style = "google",
  formatter = "google",
  format_on_save = true,
})
```

This is an intentional default change before public beta.
It prevents Lathe from surprising users by rewriting projects whose formatting contract is not Google Java Format.
