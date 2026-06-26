# Lathe — New Type Creation via Snippet Completion

Proposed M2 completion enhancement.
Builds on the completion engine in `lathe-design.md`.

## Motivation
Modern IDEs provide wizards to create new Java classes, interfaces, and enums, automatically generating the `package` declaration and class boilerplate. The standard Language Server Protocol (LSP) specification lacks a dedicated `createClass` request or native UI form capabilities. While it is possible to implement this using `workspace/executeCommand` combined with custom client-side prompts, this approach fragments the user experience, requires editor-specific plugins, and breaks out-of-the-box compatibility.

## Proposed Solution
We will implement "New Type" creation entirely within the standard LSP `textDocument/completion` endpoint. By leveraging `InsertTextFormat.Snippet`, Lathe can act as a dynamic, context-aware snippet engine. This approach instantly populates blank files with perfect boilerplate, requiring zero custom client-side configuration.

## Workflow
1. **File Creation**: The user creates a blank file (e.g., `UserService.java`) using their editor's native file explorer or command line.
2. **Completion Trigger**: The user triggers autocomplete (either automatically by typing or manually via `<C-Space>`).
3. **Context Detection**: Lathe evaluates the completion request and determines:
   - The file is empty (0 bytes or only whitespace).
   - The file name (`UserService.java`) implies a type name of `UserService`.
   - The file URI's location within the project source tree dictates the package (e.g., `package com.example.service;`).
4. **Snippet Generation**: Lathe returns four highly-prioritized `CompletionItem` objects:
   - `Class`
   - `Interface`
   - `Enum`
   - `Record`

## Technical Implementation

### CompletionItem Construction
Each completion item will be constructed as follows:
- `label`: `Class` (or `Interface`, `Enum`, `Record`)
- `kind`: `CompletionItemKind.Snippet`
- `insertTextFormat`: `InsertTextFormat.Snippet`
- `insertText`: The dynamically constructed snippet string.

**Example `insertText` payload:**
```java
"package com.example.service;\n\npublic class UserService {\n\t$0\n}"
```

### Server-Side Requirements
To support this, Lathe's completion provider must:
1. **URI Parsing**: Extract the file basename and strip the `.java` extension.
2. **Package Resolution**: Query the internal module graph or source root configurations to map the absolute file path to a valid Java package identifier.
3. **AST Safety**: Ensure the completion provider gracefully handles empty ASTs (since a 0-byte file cannot be parsed into a valid compilation unit).

## User Experience
This approach provides an elegant, zero-configuration UX. The user opens a blank file, types `c`, hits Enter, and the entire file is instantly scaffolded. The LSP client natively handles inserting the text and placing the cursor precisely at the `$0` placeholder inside the braces, ready for the user to write code.
