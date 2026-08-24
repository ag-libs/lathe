module io.github.aglibs.lathe.server {
  requires java.compiler;
  requires jdk.compiler;
  requires java.logging;
  requires org.eclipse.lsp4j;
  requires org.eclipse.lsp4j.jsonrpc;
  requires com.google.gson;
  // Gson declares `requires static jdk.unsupported` (optional). On the module path the optional
  // dependency is not pulled in automatically, leaving sun.misc.Unsafe invisible to Gson's
  // UnsafeAllocator. LSP4J types such as TypeHierarchyItem and CallHierarchyItem have no no-arg
  // constructor, so Gson must use Unsafe to instantiate them; without it deserialization throws.
  requires jdk.unsupported;
  requires com.google.googlejavaformat;
  requires io.github.aglibs.lathe.core;
  requires io.github.aglibs.validcheck;

  // Microsoft java-debug ships as a non-modular jar, so it resolves as the automatic module
  // `com.microsoft.java.debug.core`; it hosts the DAP ProtocolServer in-process. Its own deps
  // (commons-lang3, rxjava, commons-io) are read transitively as an automatic module and need no
  // explicit requires here.
  requires com.microsoft.java.debug.core;
  // The adapter's initialize subscribes to hot-code-replace events, so a no-op HCR provider is
  // registered; it returns an rxjava Observable. Hot code replace itself is a Phase 4 feature.
  requires io.reactivex.rxjava2;
  // JDI (com.sun.jdi) backs the virtual-machine-manager provider's attach connector.
  requires jdk.jdi;

  // RunTarget/LaunchOutcome cross the JSON-RPC boundary as raw records, serialized reflectively
  // by lsp4j's Gson layer -- without this, Gson can't call setAccessible on their accessors.
  opens io.github.aglibs.lathe.server.run to
      com.google.gson;

  // DebugStartResult crosses the JSON-RPC boundary the same way (§10 lathe.debug.start result).
  opens io.github.aglibs.lathe.server.debug to
      com.google.gson;

  // LatheLanguageClient is our custom JSON-RPC remote interface; lsp4j.jsonrpc reflects on its
  // methods to build the client proxy, so its package must be accessible to that module. Only the
  // public interface is exposed; the rest of the package stays package-private.
  exports io.github.aglibs.lathe.server to
      org.eclipse.lsp4j.jsonrpc;
}
