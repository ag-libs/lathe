# Lathe for Java

Lathe is a Java language server that learns your project from its build.
It runs `javac` as you edit, using the compiler settings of your Maven, Gradle, or OpenJDK `make` build.

This preview extension provides completion, compiler diagnostics, navigation, code actions, and rename, including rename across modules.
Run, test, and debug are available in the Neovim client for now.

## Getting started

1. Run the first Lathe build in your project, as the [README](https://github.com/ag-libs/lathe#first-build) shows for Maven, Gradle, and OpenJDK.
   It installs the language server and creates the `.lathe/` directory the extension looks for.
2. Open the project folder in VS Code or Cursor.
   If **Language Support for Java by Red Hat** is installed, disable it for this workspace so only one server handles your Java files.
3. Open a Java file and choose **Go to Definition** on a method call.

The [VS Code and Cursor guide](https://github.com/ag-libs/lathe/blob/main/docs/guide/editors/vscode.md) covers the JDK the server runs on, build-file changes, and known issues.

## Feedback

Report bugs and ask questions through [GitHub issues](https://github.com/ag-libs/lathe/issues).
