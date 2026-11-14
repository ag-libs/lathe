package io.github.aglibs.lathe.server.debug;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.java.debug.core.protocol.Types.CompletionItem;
import io.github.aglibs.lathe.analysis.SourceAnalysisSession;
import io.github.aglibs.lathe.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.analysis.testing.TempSourceCompiler;
import io.github.aglibs.lathe.server.module.CompilationWorker;
import io.github.aglibs.lathe.server.module.TestWorkers;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

// Debug-console completion against a real debuggee: what the user typed is placed in the stopped
// frame's method, so its locals and their members complete, written as plain text for the console.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class LatheCompletionsProviderDebuggeeTest {

  private Debuggee debuggee;
  private CompilationWorker worker;
  private LatheCompletionsProvider provider;

  @BeforeAll
  void start(@TempDir final Path dir) throws Exception {
    debuggee = Debuggee.start(dir);
    worker = TestWorkers.over(new SourceAnalysisSession(new TempSourceCompiler()));
    provider =
        new LatheCompletionsProvider(debuggee.frameSources(worker), WorkspaceTypeIndex.empty());
  }

  @AfterAll
  void close() {
    if (worker != null) {
      TestWorkers.close(worker);
    }

    if (debuggee != null) {
      debuggee.close();
    }
  }

  @Test
  void codeComplete_inTheFrame_offersItsLocalsAndTheirMembers() throws Exception {
    assertThat(labels(complete("te"))).contains("ten");
    // The console takes an expression: no statement's semicolon, no snippet tab stop.
    assertThat(complete("names.ad"))
        .extracting(item -> item.text)
        .contains("add()")
        .allSatisfy(text -> assertThat(text).doesNotContain(";", "$"));
    assertThat(provider.codeComplete(null, "te", 1, 2)).isEmpty();
  }

  private List<CompletionItem> complete(final String snippet) throws Exception {
    return provider.codeComplete(debuggee.thread().frame(0), snippet, 1, snippet.length());
  }

  private static List<String> labels(final List<CompletionItem> items) {
    return items.stream().map(item -> item.label).toList();
  }
}
