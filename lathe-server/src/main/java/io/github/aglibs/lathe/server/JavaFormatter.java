package io.github.aglibs.lathe.server;

import com.google.googlejavaformat.java.Formatter;
import com.google.googlejavaformat.java.FormatterException;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

final class JavaFormatter {

  private static final Logger LOG = Logger.getLogger(JavaFormatter.class.getName());

  private JavaFormatter() {}

  static List<TextEdit> format(final String content) {
    if (content == null) {
      return List.of();
    }
    final var t = Stopwatch.start();
    try {
      final var formatted = new Formatter().formatSourceAndFixImports(content);
      if (formatted.equals(content)) {
        LOG.fine(() -> "[format] no changes %dms".formatted(t.elapsedMs()));
        return List.of();
      }
      final var end = SourceLocator.offsetToPosition(content, content.length());
      LOG.fine(() -> "[format] applied %dms".formatted(t.elapsedMs()));
      return List.of(new TextEdit(new Range(new Position(0, 0), end), formatted));
    } catch (final FormatterException e) {
      LOG.log(Level.SEVERE, e, () -> "[format] failed");
      return List.of();
    }
  }
}
