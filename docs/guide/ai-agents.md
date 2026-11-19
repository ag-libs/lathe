# Use Lathe with your coding agent

Lathe lets your agent follow Java code across modules and check edits with the same compiler settings your build uses.
Once connected through the Model Context Protocol (MCP), the agent discovers the tools automatically.

## Connect Claude Code

1. If your project is new to Lathe, start with the [first build](../../README.md#first-build).
   If you already use Lathe in your editor, your project has the MCP launcher in `.lathe/`.
2. From the project root, register the launcher for your platform.

   **Linux or macOS**

   ```sh
   claude mcp add --transport stdio lathe -- .lathe/lathe-mcp-launcher.sh
   ```

   **Windows (PowerShell)**

   ```powershell
   claude mcp add --transport stdio lathe -- cmd.exe /d /s /c .lathe\lathe-mcp-launcher.cmd
   ```

3. Start Claude Code at the project root so the relative launcher path resolves.
   Ask **“Use Lathe to list the Java modules in this project.”**
   The answer lists the modules the build captured.

<details>
<summary>Share a registration with teammates or reuse one across projects</summary>

Add `--scope project` to the registration command to save it in `.mcp.json` for teammates on the same platform.
For one personal registration across projects on Linux or macOS, this command uses the most recently installed server.

```sh
claude mcp add --scope user --transport stdio lathe -- ~/.cache/lathe/latest/lathe-mcp-launcher.sh
```

</details>

## Connect another MCP client

Add a server named `lathe`, choose the **stdio** transport, and set its working directory to your project root.

| Platform | Command | Arguments (in order) |
| --- | --- | --- |
| Linux or macOS | `.lathe/lathe-mcp-launcher.sh` | None |
| Windows | `cmd.exe` | `/d`, `/s`, `/c`, `.lathe\lathe-mcp-launcher.cmd` |

## Try it on your code

- **Before an edit**, name a method and ask “Which classes override it, and which tests use it?”
- **After an edit**, ask “Check my Java changes and tell me which callers no longer compile.”
  Lathe checks saved files and their callers across modules and reports errors with file names and line numbers.
- **For a failing test**, name a JUnit class or method and ask the agent to run it.
  Lathe reuses the launch recorded by your build. [Test capture and supported tests](test-capture.md#requirements-and-current-limits)

Asking the agent to rename a symbol, fix imports, or format a file changes project files on disk.
To have the agent check Java edits before finishing, copy the [example project instructions](../../examples/agents/lathe-mcp.md) into your `AGENTS.md` or `CLAUDE.md`.
Claude Code also supports a [verification hook](../../examples/agents/verify-before-stop.py).

## Troubleshooting

- **Claude Code can’t find the launcher.** Start it from the directory containing `.lathe/`.
  If the launcher is missing there, run the first build.
- **Lathe reports `Sync required`.** Run the suggested command from the build root to refresh the workspace.
  A partial Maven build (for example, with `-pl`) can leave `.lathe/workspace.json` missing.
  Until a full sync records the module graph, Lathe can only check the file’s own module and refuses project-wide renames.
- **Another build or Lathe server is updating `.lathe/`.** Wait for the update to finish, then ask the agent to check your changes again.
- **Lathe reports `Not compiled`.** Fix the compiler error it reports and ask again.
  Until then, callers may still see the last version that compiled.

[Full list of Lathe’s agent tools](../status.md#ai-agent-integration-mcp)
