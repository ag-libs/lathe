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
- the version template dropped;
- `JavaInputAstVisitor` and `DimensionHelpers` moved off javac internals: internal `Flags`
  checks (enum constants, record component fields, compact constructors, implicit classes)
  replaced by public-position predicates, `JCTree.Tag` by `Tree.Kind`, the internal
  `TreeScanner` by `com.sun.source.util.TreeScanner`, and the unused
  `VarArgsOrNot.fromVariable` removed;
- every parse goes through a public `JavacTask` returning `Trees.ParsedUnit`, and positions come
  from `SourcePositions` (passed to the visitor, `DimensionHelpers`, `StringWrapper`, and
  `RemoveUnusedImports`); `operatorName`/`precedence` use `Tree.Kind` tables; annotation/modifier
  ordering moved from `AnnotationOrModifier.compareTo` into the visitor.

Further modifications will replace the remaining javac-internals seams (tokenizer
and its diagnostics, and the import/javadoc internals in `RemoveUnusedImports`) with public-API equivalents.

Per-file Apache 2.0 headers are retained. The golden fixtures under
`src/test/resources/golden/` are also copied verbatim from google-java-format
`v1.35.0` and drive the conformance suite.

## Vendored upstream tests

The upstream unit suites under `src/test/java/io/github/aglibs/lathe/format/gjf/**`
(and their resources under `src/test/resources/io/github/aglibs/lathe/format/gjf/`)
are copied from google-java-format `v1.35.0` `core/src/test` and modified only by
the same package rename (including resource paths). They stay JUnit 4 + Truth and
run on the JUnit Vintage engine, so upstream merges apply cleanly.

The pruned command-line front end (`Main`, `CommandLineOptions`,
`CommandLineOptionsParser`, `FormatFileCallable`, `UsageException`) is restored in
**test scope only**, together with a `GoogleJavaFormatVersion` generated from the
upstream template, because many upstream formatting tests drive the formatter
through `Main`. It is not part of the published library.

Not vendored: `MainTest` (uses javac internals directly), `CommandLineOptionsParserTest`,
`CommandLineFlagsTest`, `GoogleJavaFormatToolTest`, `GoogleJavaFormatToolProviderTest`
(CLI and tool-provider behavior, not formatting), and `FormatterIntegrationTest`
(superseded by `GoldenConformanceTest`).
