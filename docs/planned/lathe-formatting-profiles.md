# Lathe — Formatting and Indentation Profiles

## Status

Planned.
This design changes Lathe's default formatting behavior from "Google Java Format on save" to an explicit,
project-sensitive choice.

The goal is to avoid rewriting projects that do not use Google Java Format while keeping Lathe's Java indentation useful
during normal editing.

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
- Implement `textDocument/onTypeFormatting`.
- Fix range formatting.
- Dynamically re-register formatting capabilities after initialization.
- Support every `.editorconfig` glob rule in the first slice.

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
```

Defaults:

```lua
indent_style = "editor_config"
formatter = nil
format_on_save = false
```

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

The Neovim plugin should walk upward from the buffer path and read the nearest `.editorconfig`.
The first slice only needs:

- `root = true`
- `[*]`
- `[*.java]`
- `indent_style`
- `indent_size`
- `tab_width`

For Java buffers:

- `indent_style = space`, `indent_size = N`:
  use spaces, block indent `N`, continuation indent `2N`.
- `indent_style = tab`:
  use tabs, block indent from `indent_size` when numeric, otherwise `tab_width`, otherwise `4`.
  Continuation indent remains twice the block width in display columns.
- missing or unsupported values:
  fallback to block indent `4`, continuation indent `8`.

Dropwizard's `.editorconfig` therefore gives 4-space block indentation and 8-space continuation indentation.

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

## Neovim Plugin Changes

`lathe.lua` should own user options and pass indentation settings into `lathe.indent`.

Current behavior:

- `format_on_save` defaults to true.
- `ftplugin/java.lua` forces `shiftwidth = 2`, `softtabstop = 2`, and `tabstop = 2`.
- `lathe.indent` has hardcoded Google-shaped constants.

Planned behavior:

- `format_on_save` defaults to false.
- `formatter` defaults to nil.
- `ftplugin/java.lua` should keep Java indentation wiring but stop hardcoding Google indentation widths.
- `lathe.indent` should expose a setup/config function and keep profile state in the module.
- The plugin should apply buffer-local `expandtab`, `shiftwidth`, `softtabstop`, and `tabstop` according to the
  resolved indentation profile.
- The plugin should install the save-format autocmd only when:

```lua
opts.formatter == "google" and opts.format_on_save == true
```

The setup function should also send server initialization options:

```lua
init_options = {
  lathe = {
    formatter = opts.formatter,
  },
}
```

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

## Tests

Neovim tests:

- default setup uses `editor_config`.
- a project `.editorconfig` with `indent_size = 4` yields 4-space block indentation.
- `indent_style = "google"` preserves the current 2/4 behavior.
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
