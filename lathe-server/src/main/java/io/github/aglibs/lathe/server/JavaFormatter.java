package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Logger;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

final class JavaFormatter {

  private static final Logger LOG = Logger.getLogger(JavaFormatter.class.getName());

  private JavaFormatter() {}

  // Builds the minimal edit, or empty when already formatted. Throws the engine's native failure;
  // WorkspaceSession.format catches it to log and notify the client.
  static List<TextEdit> format(final FormatEngine engine, final String content, final Path file)
      throws Exception {
    return edits(engine, content, () -> engine.format(content, file));
  }

  // Formats only the given ranges (LSP positions) of content; text outside them is not touched.
  static List<TextEdit> formatRanges(
      final GjfFormatEngine engine, final String content, final List<Range> ranges)
      throws Exception {
    return edits(
        engine,
        content,
        () ->
            engine.formatRanges(
                content, ranges.stream().map(range -> toTextRange(content, range)).toList()));
  }

  private static List<TextEdit> edits(
      final FormatEngine engine, final String content, final Callable<String> formatting)
      throws Exception {
    if (content == null) {
      return List.of();
    }

    final var t = Stopwatch.start();
    final String formatted = formatting.call();
    final String engineType = engine.getClass().getSimpleName();
    if (formatted.equals(content)) {
      LOG.fine(() -> "[format] %s no changes %dms".formatted(engineType, t.elapsedMs()));
      return List.of();
    }

    LOG.fine(() -> "[format] %s applied %dms".formatted(engineType, t.elapsedMs()));
    return List.of(minimalEdit(content, formatted));
  }

  private static TextRange toTextRange(final String content, final Range range) {
    return new TextRange(offsetOf(content, range.getStart()), offsetOf(content, range.getEnd()));
  }

  private static int offsetOf(final String content, final Position position) {
    return SourceLocator.toOffset(content, position.getLine(), position.getCharacter());
  }

  // Replaces only the region that actually changed (between the common leading and trailing text),
  // not the whole document. A whole-document edit makes the editor replace every line, forcing a
  // full re-highlight/diagnostic refresh that reads as flicker on each save.
  private static TextEdit minimalEdit(final String content, final String formatted) {
    final int contentLength = content.length();
    final int formattedLength = formatted.length();
    final int max = Math.min(contentLength, formattedLength);
    int prefix = 0;
    while (prefix < max && content.charAt(prefix) == formatted.charAt(prefix)) {
      prefix++;
    }

    int suffix = 0;
    while (suffix < max - prefix
        && content.charAt(contentLength - 1 - suffix)
            == formatted.charAt(formattedLength - 1 - suffix)) {
      suffix++;
    }

    final var start = SourceLocator.offsetToPosition(content, prefix);
    final var end = SourceLocator.offsetToPosition(content, contentLength - suffix);
    return new TextEdit(
        new Range(start, end), formatted.substring(prefix, formattedLength - suffix));
  }
}
