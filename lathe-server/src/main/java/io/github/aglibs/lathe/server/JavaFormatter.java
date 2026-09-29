package io.github.aglibs.lathe.server;

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

  static List<TextEdit> format(final FormatEngine engine, final String content) {
    if (content == null) {
      return List.of();
    }

    final String engineType = engine.getClass().getSimpleName();
    final var t = Stopwatch.start();
    try {
      final String formatted = engine.format(content);
      if (formatted.equals(content)) {
        LOG.fine(() -> "[format] %s no changes %dms".formatted(engineType, t.elapsedMs()));
        return List.of();
      }

      final var end = SourceLocator.offsetToPosition(content, content.length());
      LOG.fine(() -> "[format] %s applied %dms".formatted(engineType, t.elapsedMs()));
      return List.of(new TextEdit(new Range(new Position(0, 0), end), formatted));
    } catch (final Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }

      LOG.log(
          Level.SEVERE, e, () -> "[format] %s failed %dms".formatted(engineType, t.elapsedMs()));
      return List.of();
    }
  }
}
