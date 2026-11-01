## Lathe MCP tools

This project has the Lathe MCP server (`lathe`): javac-accurate answers on the build's own classpath, across all modules.
Prefer it over text search where the compiler knows better; plain `grep` is fine for everything else.

### Use Lathe for

- **Renaming** a Java symbol: `rename_symbol`. Never search-and-replace a Java name.
- **Checking an edit**: after changing Java files, run `verify_change` (no arguments) before you finish.
  It compiles what you changed plus its callers in every module, without a Maven build.
- **Imports**: `fix_imports` after adding code that uses new types.
- **Formatting**: `format_file` on a file you edited, so it passes the build's formatting check.
- **Before changing a method's signature or behaviour**: `analyze_change`, for its overrides, users and relevant tests.
- **Overloaded or polymorphic names**: `find_references`, `find_implementations` and `call_hierarchy`,
  where grep would mix up same-named methods.
- **One test**: `run_test` replays a test class or method without a reactor build.

### How to call them

- Positions are 1-based and must point at a name, not a keyword or whitespace;
  give the name as `symbol` with the `line` instead of counting the column (`outline` gives `line:column`).
- Start with `workspace_summary` if you do not know the module layout.
- Read the note at the end of a result:
  `Not compiled:` means fix the code; `Sync required:` and `Not checked:` give the command to run when a sync helps.
