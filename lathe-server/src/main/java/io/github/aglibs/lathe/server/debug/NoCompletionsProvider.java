package io.github.aglibs.lathe.server.debug;

import com.microsoft.java.debug.core.adapter.ICompletionsProvider;
import com.microsoft.java.debug.core.protocol.Types.CompletionItem;
import com.sun.jdi.StackFrame;
import java.util.List;

/**
 * The completions provider for the no-arg handshake context, which has no workspace to complete
 * against. The adapter's {@code attach} handler requires an {@link ICompletionsProvider} to be
 * registered; a live session uses {@link LatheCompletionsProvider} instead.
 */
final class NoCompletionsProvider implements ICompletionsProvider {

  @Override
  public List<CompletionItem> codeComplete(
      final StackFrame frame, final String snippet, final int line, final int column) {
    return List.of();
  }
}
