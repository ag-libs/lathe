package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;

/**
 * In-process facade into the language server for non-editor clients (the MCP server): it drives the
 * same {@link WorkspaceSession} analysis the LSP handlers do, reading each file from disk so it
 * reflects out-of-process edits, and returns results directly instead of over JSON-RPC.
 */
public final class LatheEngine {

  private static final long REQUEST_TIMEOUT_SECONDS = 30;
  private static final int SNIPPET_CONTEXT_LINES = 3;

  // Every put creates a fresh generation, so the LSP version is irrelevant to freshness here.
  private static final int VERSION = 1;

  private final LatheTextDocumentService service = new LatheTextDocumentService();
  private final Path workspaceRoot;
  private final Path latheDir;

  public LatheEngine(final Path workspaceRoot) {
    this.workspaceRoot = workspaceRoot;
    this.latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    service.connect(new NoopLanguageClient());
    service.initialize(workspaceRoot);
  }

  /** Compiler diagnostics for {@code file} at its current on-disk content. */
  public List<Diagnostic> diagnostics(final Path file) {
    return compileFromDisk(file);
  }

  /**
   * Definition target(s) for the symbol at {@code line}/{@code column} (0-based), each enriched
   * with a source snippet and where it resolved.
   */
  public List<LatheLocation> definition(final Path file, final int line, final int column) {
    compileFromDisk(file); // register the file and warm its analysis before resolving
    final var params =
        new DefinitionParams(
            new TextDocumentIdentifier(file.toUri().toString()), new Position(line, column));
    final var either = await(service.definition(params));
    final List<? extends Location> locations = either.isLeft() ? either.getLeft() : List.of();
    return locations.stream().map(this::toLatheLocation).toList();
  }

  private List<Diagnostic> compileFromDisk(final Path file) {
    final var uri = file.toUri().toString();
    final String content = IOUtil.unchecked(() -> Files.readString(file));
    return await(service.diagnosticsFuture(uri, content, VERSION));
  }

  public void close() {
    service.close();
  }

  private LatheLocation toLatheLocation(final Location location) {
    final var targetPath = LatheUri.toPath(location.getUri());
    return new LatheLocation(
        location.getUri(),
        location.getRange(),
        origin(targetPath),
        snippet(targetPath, location.getRange()));
  }

  private LatheLocation.Origin origin(final Path target) {
    if (target.startsWith(latheDir)) {
      return LatheLocation.Origin.GENERATED;
    }

    if (target.startsWith(workspaceRoot)) {
      return LatheLocation.Origin.REACTOR;
    }

    return LatheLocation.Origin.EXTERNAL;
  }

  private static String snippet(final Path file, final Range range) {
    final List<String> lines = IOUtil.unchecked(() -> Files.readAllLines(file));
    final int target = range.getStart().getLine();
    final int from = Math.max(0, target - SNIPPET_CONTEXT_LINES);
    final int to = Math.min(lines.size() - 1, target + SNIPPET_CONTEXT_LINES);
    return IntStream.rangeClosed(from, to)
        .mapToObj(i -> "%s%d  %s".formatted(i == target ? "> " : "  ", i + 1, lines.get(i)))
        .collect(Collectors.joining(System.lineSeparator()));
  }

  private static <T> T await(final CompletableFuture<T> future) {
    try {
      return future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("[engine] request interrupted", e);
    } catch (final ExecutionException | TimeoutException e) {
      throw new IllegalStateException("[engine] request failed", e);
    }
  }
}
