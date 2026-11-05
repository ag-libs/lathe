// Lathe's javac-backed analysis: what the language server's features compute from a compile.
// It holds no session state; the server drives it from its per-module workers.
module io.github.aglibs.lathe.analysis {
  requires transitive java.compiler;
  requires transitive jdk.compiler;
  requires transitive java.logging;
  requires transitive org.eclipse.lsp4j;
  requires transitive org.eclipse.lsp4j.jsonrpc;
  requires transitive com.google.gson;
  requires transitive io.github.aglibs.lathe.core;
  requires io.github.aglibs.validcheck;

  exports io.github.aglibs.lathe.analysis;
  exports io.github.aglibs.lathe.analysis.completion;

  // TypeHierarchyExplorerResult (the lathe.typeHierarchy command result) and RunTarget cross the
  // JSON-RPC boundary as raw records; Gson reflects on their accessors.
  opens io.github.aglibs.lathe.analysis to
      com.google.gson;
}
