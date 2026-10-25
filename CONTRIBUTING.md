# Contributing to Lathe

Thanks for your interest in Lathe.
Bug reports, questions and ideas are all welcome.

## Reporting a bug

[Open an issue](https://github.com/ag-libs/lathe/issues).
The most useful things to include are the Lathe version, your editor, your JDK and Maven versions,
and the server log from a run with `LATHE_DEBUG=1` set before you start the editor.
A small project that shows the problem helps most of all.

For a security problem, don't open a public issue; see [SECURITY.md](SECURITY.md).

## Contributing code

Please open an issue before you start on a pull request, so we can agree on the change first.
That saves you from writing code that ends up not fitting the design.

### Building

Lathe runs on JDK 17+, but building it needs JDK 21+.

```bash
mvn install -DskipTests          # build everything
mvn verify                       # all tests, including the Maven integration tests
mvn spotless:apply               # format the code; spotless:check runs on verify
```

The `lathe-neovim` module runs its specs in a headless Neovim, so it needs `nvim` on your `PATH`.
To build without the editor clients, add `-pl '!lathe-neovim,!lathe-vscode'`.

### Where things are

- [docs/guide/how-it-works.md](docs/guide/how-it-works.md) explains the overall approach.
- [docs/design-index.md](docs/design-index.md) links the design document for each feature.
- [AGENTS.md](AGENTS.md) lists the modules and the coding and testing conventions.
  It is written for AI coding agents, but the rules apply to every change.

## AI-assisted development

Lathe is developed with AI coding tools, mainly Claude Code.
A maintainer reviews every change and takes responsibility for it, the same as for hand-written code.
Commits produced with AI assistance carry a `Co-Authored-By` trailer, so the provenance stays visible
in the git history.

AI-generated output is used only where it is compatible with the project's Apache-2.0 license, in line
with the [Apache Software Foundation's generative-tooling guidance](https://www.apache.org/legal/generative-tooling.html).
You're welcome to use AI tools for your own contributions under the same rules.

## Releases

Maintainers: the release process is in [RELEASING.md](RELEASING.md).
