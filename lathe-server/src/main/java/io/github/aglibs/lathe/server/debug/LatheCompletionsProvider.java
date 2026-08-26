package io.github.aglibs.lathe.server.debug;

import com.microsoft.java.debug.core.adapter.ICompletionsProvider;
import com.microsoft.java.debug.core.protocol.Types.CompletionItem;
import com.sun.jdi.StackFrame;
import java.util.List;

/**
 * A no-op debug-console completions provider. The adapter's {@code attach} handler requires one to
 * be registered; debug-console code completion is a later phase, so this stub offers nothing.
 */
final class LatheCompletionsProvider implements ICompletionsProvider {

  @Override
  public List<CompletionItem> codeComplete(
      final StackFrame frame, final String snippet, final int line, final int column) {
    return List.of();
  }
}
