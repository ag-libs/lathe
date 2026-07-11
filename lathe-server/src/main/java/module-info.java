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

  // RunTarget/ReplayOutcome cross the JSON-RPC boundary as raw records, serialized reflectively
  // by lsp4j's Gson layer -- without this, Gson can't call setAccessible on their accessors.
  opens io.github.aglibs.lathe.server.run to
      com.google.gson;

  // LatheLanguageClient is our custom JSON-RPC remote interface; lsp4j.jsonrpc reflects on its
  // methods to build the client proxy, so its package must be accessible to that module. Only the
  // public interface is exposed; the rest of the package stays package-private.
  exports io.github.aglibs.lathe.server to
      org.eclipse.lsp4j.jsonrpc;
}
