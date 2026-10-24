package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.WorkspaceStyle;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.core.schema.WorkspaceStyleData;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.logging.Logger;

// Builds the format engine a formatter spec names, for the editor's server and the agent engine
// alike; null when the spec names none or its formatter cannot run yet.
final class FormatEngines {

  private static final Logger LOG = Logger.getLogger(FormatEngines.class.getName());

  private FormatEngines() {}

  static FormatterSpec workspaceSpec(final Path workingDir) {
    if (workingDir == null) {
      return null;
    }

    try {
      final WorkspaceStyleData style = WorkspaceStyle.read(workingDir);
      return style != null ? style.formatter() : null;
    } catch (final IOException e) {
      LOG.warning(() -> "[initialize] malformed style file: %s".formatted(e.getMessage()));
      return null;
    }
  }

  static FormatEngine forSpec(
      final FormatterSpec spec, final Path workingDir, final LatheTextDocumentService service) {
    return switch (spec.engine()) {
      case LatheFlags.FORMATTER_GOOGLE, LatheFlags.FORMATTER_AOSP ->
          pinned(
              spec,
              () ->
                  gjfEngine(
                      spec, GjfFormatEngine.Family.GOOGLE, spec.engine().toUpperCase(Locale.ROOT)));
      case LatheFlags.FORMATTER_PALANTIR ->
          pinned(
              spec,
              () ->
                  gjfEngine(
                      spec, GjfFormatEngine.Family.PALANTIR, LatheFlags.FORMAT_STYLE_PALANTIR));
      case LatheFlags.FORMATTER_ECLIPSE ->
          pinned(
              spec,
              () ->
                  new EclipseFormatEngine(
                      spec.options().getOrDefault(LatheFlags.FORMAT_FILE, ""), spec.classpath()));
      case LatheFlags.FORMATTER_COMMAND -> commandEngine(spec.command(), workingDir);
      case LatheFlags.FORMATTER_COMMAND_FILE ->
          fileCommandEngine(spec.command(), workingDir, service);
      default -> null;
    };
  }

  // The project's pinned formatter is unavailable until lathe:sync has resolved its jars.
  private static FormatEngine pinned(
      final FormatterSpec spec, final Supplier<FormatEngine> engine) {
    if (spec.classpath().isEmpty()) {
      LOG.warning(
          () ->
              "[initialize] %s formatter has no resolved classpath; run %s"
                  .formatted(spec.engine(), LatheLayout.SYNC_SCRIPT_COMMAND));
      return null;
    }

    return engine.get();
  }

  // The style is the Spotless step's, else the engine's own (a hand-written style file may omit
  // it).
  private static FormatEngine gjfEngine(
      final FormatterSpec spec, final GjfFormatEngine.Family family, final String defaultStyle) {
    return new GjfFormatEngine(
        family,
        spec.options().getOrDefault(LatheFlags.FORMAT_STYLE, defaultStyle),
        spec.options(),
        spec.classpath());
  }

  private static FormatEngine commandEngine(final List<String> command, final Path workingDir) {
    if (command.isEmpty()) {
      return null;
    }

    return new ExternalCommandFormatEngine(
        command, ExternalCommandFormatEngine.DEFAULT_TIMEOUT, workingDir);
  }

  private static FormatEngine fileCommandEngine(
      final List<String> command, final Path workingDir, final LatheTextDocumentService service) {
    if (command.isEmpty() || workingDir == null) {
      return null;
    }

    return new FileCommandFormatEngine(
        command,
        workingDir,
        FileCommandFormatEngine.DEFAULT_TIMEOUT,
        service::moduleRelOf,
        service::buildTool);
  }
}
