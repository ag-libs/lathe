package io.github.aglibs.lathe.server.debug;

import com.microsoft.java.debug.core.adapter.ICompletionsProvider;
import com.microsoft.java.debug.core.protocol.Types.CompletionItem;
import com.sun.jdi.Location;
import com.sun.jdi.StackFrame;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.server.analysis.completion.CompletionOutcome;
import io.github.aglibs.lathe.server.module.CompilationWorker;
import io.github.aglibs.lathe.server.module.WorkspaceModuleRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionTriggerKind;
import org.eclipse.lsp4j.InsertReplaceEdit;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * Debug-console code completion (DB-4): answers the DAP {@code completions} request for the REPL /
 * watches by splicing the typed snippet into the suspended frame's source line and running Lathe's
 * ordinary completion engine there — so the frame's locals, parameters, {@code this}, fields, and
 * visible types complete exactly as in the editor. Pure read-only source analysis: it uses only the
 * frame's <em>location</em> (file + line for lexical scope), never the debuggee's runtime state, so
 * unlike evaluation it runs no debuggee code and takes no invocation lock. Any failure yields an
 * empty list — a bad snippet never breaks the session.
 */
final class LatheCompletionsProvider implements ICompletionsProvider {

  private static final Logger LOG = Logger.getLogger(LatheCompletionsProvider.class.getName());

  // The snippet is spliced as the initializer of a throwaway local so it sits in an expression
  // position -- where the completion engine resolves locals, params, and members. A bare statement
  // would only surface keywords. The initializer is placed on its own line (below this one) so the
  // snippet still starts at column 0 and the LSP replacement range maps straight to DAP
  // start/length.
  private static final String INIT_STATEMENT = "var __LATHE_COMPLETE__ =";

  private final FrameSources frames;
  private final WorkspaceTypeIndex typeIndex;

  LatheCompletionsProvider(
      final WorkspaceModuleRegistry workspace,
      final List<Path> sourceRoots,
      final WorkspaceTypeIndex typeIndex) {
    this.frames = new FrameSources(workspace, sourceRoots);
    this.typeIndex = typeIndex;
  }

  @Override
  public List<CompletionItem> codeComplete(
      final StackFrame frame, final String snippet, final int line, final int column) {
    if (frame == null || snippet == null) {
      return List.of();
    }

    try {
      return complete(frame, snippet, column);
    } catch (final Exception e) {
      LOG.log(Level.FINE, e, () -> "[completion:dap] failed for '%s'".formatted(snippet));
      return List.of();
    }
  }

  private List<CompletionItem> complete(
      final StackFrame frame, final String snippet, final int column) throws Exception {
    final Location location = frame.location();
    final Path file = frames.fileFor(location).orElse(null);
    if (file == null) {
      return List.of();
    }

    final CompilationWorker worker = frames.workerFor(file).orElse(null);
    if (worker == null) {
      return List.of();
    }

    final String content = Files.readString(file);
    // Splice `var __LATHE_COMPLETE__ =` at the frame's line and the snippet on the next line, so
    // the
    // snippet is an initializer expression in the method's lexical scope. The snippet keeps column
    // 0,
    // so its own line is where the cursor sits; the column is a 0-based offset into the snippet.
    final int frameLine = location.lineNumber() - 1;
    final int lineStart = SourceLocator.toOffset(content, frameLine, 0);
    final int snippetLine = frameLine + 1;
    final int cursorColumn = Math.max(0, Math.min(column, snippet.length()));
    final String synthetic =
        content.substring(0, lineStart)
            + INIT_STATEMENT
            + "\n"
            + snippet
            + "\n"
            + content.substring(lineStart);
    final var position = new Position(snippetLine, cursorColumn);
    // The engine derives context (member vs. name) and every item's replacement range from the
    // content itself via its sentinel pipeline, so it ignores this trigger kind — Invoked is a
    // neutral constant, not an inference from the snippet text.
    final CompletionOutcome outcome =
        worker
            .completeTransient(
                file.toUri().toString(),
                content,
                synthetic,
                position,
                new CompletionContext(CompletionTriggerKind.Invoked),
                typeIndex,
                List.of())
            .join();

    // The engine attaches a javac-derived replacement range to every item, which toDapItem reads;
    // the fallback (a pure insert at the cursor) is only reached if an item carries no range.
    return outcome.items().stream()
        .map(item -> toDapItem(item, snippetLine, cursorColumn, 0))
        .toList();
  }

  /**
   * Maps one LSP completion item to the DAP shape. The synthetic snippet occupies {@code
   * cursorLine} from column 0, so an LSP replacement range on that line already reads as a
   * snippet-relative {@code start}/{@code number}; when the engine attaches no edit, the
   * identifier-prefix fallback is used.
   */
  static CompletionItem toDapItem(
      final org.eclipse.lsp4j.CompletionItem item,
      final int cursorLine,
      final int fallbackStart,
      final int fallbackNumber) {
    final Range range = replaceRange(item);
    int start = fallbackStart;
    int number = fallbackNumber;
    if (range != null && range.getStart().getLine() == cursorLine) {
      start = range.getStart().getCharacter();
      number = range.getEnd().getCharacter() - range.getStart().getCharacter();
    }

    final var dap = new CompletionItem(item.getLabel(), insertText(item));
    dap.type = kindToType(item.getKind());
    dap.sortText = item.getSortText();
    dap.start = start;
    dap.number = number;
    return dap;
  }

  private static Range replaceRange(final org.eclipse.lsp4j.CompletionItem item) {
    final Either<TextEdit, InsertReplaceEdit> edit = item.getTextEdit();
    if (edit == null) {
      return null;
    }

    if (edit.isLeft()) {
      return edit.getLeft().getRange();
    }

    final InsertReplaceEdit insertReplace = edit.getRight();
    return insertReplace.getReplace() != null
        ? insertReplace.getReplace()
        : insertReplace.getInsert();
  }

  private static String insertText(final org.eclipse.lsp4j.CompletionItem item) {
    if (item.getInsertText() != null) {
      return item.getInsertText();
    }

    final Either<TextEdit, InsertReplaceEdit> edit = item.getTextEdit();
    if (edit != null && edit.isLeft()) {
      return edit.getLeft().getNewText();
    }

    return item.getLabel();
  }

  private static String kindToType(final CompletionItemKind kind) {
    if (kind == null) {
      return "value";
    }

    return switch (kind) {
      case Method, Function -> "method";
      case Constructor -> "constructor";
      case Field, EnumMember -> "field";
      case Variable -> "variable";
      case Class -> "class";
      case Interface -> "interface";
      case Module -> "module";
      case Property -> "property";
      case Enum -> "enum";
      case Keyword -> "keyword";
      case Snippet -> "snippet";
      case Text -> "text";
      default -> "value";
    };
  }
}
