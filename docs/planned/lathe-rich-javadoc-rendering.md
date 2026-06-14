# Rich Javadoc Rendering

## 1. Vision & Scope

Lathe currently displays Javadoc in hover and completion documentation as raw text. It extracts the raw comment string using `Trees.getDocComment()` and applies manual regex stripping to remove `/**` and `*` characters. This leaves HTML tags (e.g., `<b>`, `<pre>`), block tags (e.g., `@param`, `@return`), and inline tags (e.g., `{@link}`) unparsed and unformatted, providing a suboptimal user experience.

The goal of this design is to provide native, pixel-perfect Markdown formatting for Javadoc using the `javac` compiler's built-in AST parser (`DocTrees` and `DocTreeScanner`), eliminating regex hacks and avoiding heavy external HTML-to-Markdown libraries.

## 2. Approach

We will replace the string-based regex cleanup with an AST-walking Markdown generator. This approach ensures 100% fidelity to the Javadoc specification and handles malformed HTML gracefully, as `javac` already parses it into a structured tree.

### 2.1 Upgrade to `DocTrees`

The `com.sun.source.util.Trees` API only provides `String getDocComment(TreePath path)`. To access the Javadoc AST, we must use `com.sun.source.util.DocTrees`, which extends `Trees`.

- **Initialization:** Update instances of `Trees.instance(task)` to `DocTrees.instance(task)`.
- **Extraction:** In `JavadocLocator.locate()`, use `docTrees.getDocCommentTree(path)` to obtain a `DocCommentTree`.

### 2.2 `JavadocMarkdownPrinter`

Introduce a new utility, `JavadocMarkdownPrinter`, extending `DocTreeScanner<Void, Void>`. This visitor walks the `DocCommentTree` and appends Markdown formatting to a `StringBuilder`.

Key mappings:
- **`visitStartElement` / `visitEndElement`**: Map `<b>` / `<strong>` to `**`, `<i>` / `<em>` to `*`, `<code>` to ``` ` ```, and `<pre>` to fenced code blocks (```` ```java ````).
- **`visitText`**: Append raw text, handling leading spaces.
- **`visitLink` / `visitSee`**: Format `{@link Foo}` as inline code or standard text, scanning the label if present, or defaulting to the reference signature.
- **`visitParam` / `visitReturn` / `visitThrows`**: Format block tags with a bold prefix (e.g., `**@param** ` + name) followed by the scanned description.

### 2.3 Integration

- **`JavadocLocator`**: Change `locate()` to return the formatted Markdown string by passing the retrieved `DocCommentTree` to `JavadocMarkdownPrinter.format()`.
- **`HoverFormatter`**: Remove the `cleanDoc()` regex hack. The formatter will simply append the already-formatted Markdown string to the `MarkupContent`.
- **Completion**: Ensure that completion item `documentation` also utilizes `JavadocLocator` to provide rich Markdown previews during autocomplete.

## 3. Signature Help Impact

Switching to `DocTrees` also unlocks `ParameterInformation.documentation` in signature help.
Currently `SignatureInformation.documentation` receives the full cleaned javadoc string (main
description + all `@param`/`@return` tags concatenated). Per-parameter docs are never set.

Once `JavadocMarkdownPrinter` exposes the block tags as structured data, `buildSignature` in
`SignatureHelpResolver` can:

- Set `SignatureInformation.documentation` from the **main description only** (everything before
  the first block tag) — so the popup stays concise.
- Set `ParameterInformation.documentation` for each param from the matching `@param name …`
  block — editors (Neovim, VS Code) swap the visible description as the cursor moves between
  argument slots, giving tight per-argument feedback that matches jdtls behaviour.

This requires `JavadocMarkdownPrinter` to expose two entry points: one that formats the full
doc (used by hover and completion), and one that returns the `@param` descriptions as a
`Map<String, String>` keyed by parameter name (used by `buildSignature`).

## 4. Prior Art & Constraints

This implementation is inspired by George Fraser's `java-language-server`, which proved that `DocTreeScanner` can generate comprehensive Markdown in under 300 lines of code.

**Constraints:**
- No external libraries (e.g., `commonmark-java`) are permitted. The entire transformation must remain within `lathe-server` and rely exclusively on the `jdk.compiler` module.
- Formatting should prioritize readability. Overly complex HTML structures (like nested tables) can be degraded gracefully into standard text.
