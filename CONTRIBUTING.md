# Contributing to Lathe

Bug reports, questions, and feature requests are welcome through [GitHub issues](https://github.com/ag-libs/lathe/issues).
For a security concern, follow the private reporting instructions in the [security policy](SECURITY.md).

## Reporting a bug

Describe the action you took, what you expected, and what happened.
Include your editor, Lathe version, JDK version, and Maven or Gradle version.
For an editor issue, start the editor with `LATHE_DEBUG=1`, reproduce the problem, and include the relevant lines from the server log.
A small project that reproduces the problem helps a maintainer try the same steps. [Troubleshooting](docs/guide/troubleshooting.md)

## Ideas and feature requests

Describe what you want to do in your project and where the current workflow gets in the way.

## Pull requests

External code contributions are currently closed.
If you have a fix, describe it in the issue so a maintainer can review it.

## AI-assisted development

Lathe is developed with AI coding tools, mainly Claude Code.
A maintainer reviews every change and takes responsibility for it, the same as for hand-written code.
Commits produced with AI assistance carry a `Co-Authored-By` trailer, so the provenance stays visible in the git history.

AI-generated output is used only where it is compatible with the project's Apache-2.0 license, in line with the [Apache Software Foundation's generative-tooling guidance](https://www.apache.org/legal/generative-tooling.html).

## Releases

Maintainers: the release process is in [RELEASING.md](RELEASING.md).
