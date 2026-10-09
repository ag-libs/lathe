# lathe-format

A JDK-resilient Java source formatter. It is a **fork of
[google-java-format](https://github.com/google/google-java-format) `v1.35.0`**
(Copyright Google Inc., Apache License 2.0), being incrementally re-hosted off
`com.sun.tools.javac` internals onto the public Compiler Tree API so it needs no
`--add-exports` and does not break on JDK updates. It is bundled by `lathe-server`
and publishable standalone.

## Fork provenance and modifications

The sources under `io/github/aglibs/lathe/format/gjf/**` are copied from
google-java-format `v1.35.0` (`core/src/main/java/com/google/googlejavaformat/**`)
and modified as follows:

- package `com.google.googlejavaformat` renamed to `io.github.aglibs.lathe.format.gjf`;
- the command-line front end pruned (`Main`, `CommandLineOptions*`, `*Tool*`,
  `FormatFileCallable`, `UsageException`) — this module is a library;
- the version template dropped.

Further modifications will replace the javac-internals seams (tokenizer, parse
invocation, position access, flag inspection) with public-API equivalents.

Per-file Apache 2.0 headers are retained. The golden fixtures under
`src/test/resources/golden/` are also copied verbatim from google-java-format
`v1.35.0` and drive the conformance suite.
