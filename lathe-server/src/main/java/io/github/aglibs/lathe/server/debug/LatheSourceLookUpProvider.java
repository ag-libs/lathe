package io.github.aglibs.lathe.server.debug;

import com.microsoft.java.debug.core.JavaBreakpointLocation;
import com.microsoft.java.debug.core.adapter.ISourceLookUpProvider;
import com.microsoft.java.debug.core.adapter.Source;
import com.microsoft.java.debug.core.adapter.SourceType;
import com.microsoft.java.debug.core.protocol.Types;
import io.github.aglibs.lathe.analysis.TypeSourceLocator;
import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.lathe.server.module.CompilationWorker;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Maps between the debug adapter and Lathe's source model: a source line to the enclosing class
 * binary name (so JDI can arm a breakpoint) and a class name back to its source file. Each source
 * URI is routed through the workspace to its owning module's {@link CompilationWorker} — main,
 * test, or an upstream reactor module — reusing the attributed analysis of the open file so nested
 * and anonymous classes resolve to the exact binary name javac assigns, or, when the file has none
 * cached, parsing it from disk for its top-level type, whose nested classes java-debug arms too
 * (DB-7). A file belongs to exactly one source tree, so the routing is unambiguous. Class-to-source
 * lookup still spans the launched module's source roots (cross-module reverse lookup is Phase 2);
 * expression support is deferred.
 */
public final class LatheSourceLookUpProvider implements ISourceLookUpProvider {

  private static final Logger LOG = Logger.getLogger(LatheSourceLookUpProvider.class.getName());

  private final FrameSources frames;

  public LatheSourceLookUpProvider(final FrameSources frames) {
    this.frames = frames;
  }

  @Override
  public boolean supportsRealtimeBreakpointVerification() {
    return false;
  }

  @Override
  public JavaBreakpointLocation[] getBreakpointLocations(
      final String sourceUri, final Types.SourceBreakpoint[] breakpoints) {
    final String uri = toUri(sourceUri);
    final Map<Integer, String> classNames =
        classNamesAt(uri, Arrays.stream(breakpoints).map(breakpoint -> breakpoint.line).toList());
    return Arrays.stream(breakpoints)
        .map(breakpoint -> breakpointLocation(uri, breakpoint, classNames.get(breakpoint.line)))
        .toArray(JavaBreakpointLocation[]::new);
  }

  @Override
  public Source getSource(final String fullyQualifiedName, final String sourcePath) {
    return new Source(sourceUri(fullyQualifiedName), SourceType.LOCAL);
  }

  @Override
  public String getSourceContents(final String uri) {
    try {
      return Files.readString(toPath(uri));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public List<MethodInvocation> findMethodInvocations(final String uri, final int line) {
    return List.of();
  }

  // The interface still declares these abstract methods as deprecated; a concrete provider must
  // implement them even though the adapter prefers getBreakpointLocations / getSource, so they
  // delegate to the same non-deprecated logic.
  @Override
  @SuppressWarnings("deprecation")
  public String[] getFullyQualifiedName(
      final String sourceFilePath, final int[] lines, final int[] columns) {
    final String uri = toUri(sourceFilePath);
    final Map<Integer, String> classNames =
        classNamesAt(uri, Arrays.stream(lines).boxed().toList());
    return Arrays.stream(lines)
        .mapToObj(line -> classNames.getOrDefault(line, ""))
        .toArray(String[]::new);
  }

  @Override
  @SuppressWarnings("deprecation")
  public String getSourceFileURI(final String fullyQualifiedName, final String sourcePath) {
    return sourceUri(fullyQualifiedName);
  }

  // className: null when the line has none, and the breakpoint cannot be armed.
  private static JavaBreakpointLocation breakpointLocation(
      final String uri, final Types.SourceBreakpoint breakpoint, final String className) {
    final var location = new JavaBreakpointLocation(breakpoint.line, breakpoint.column);
    if (className == null) {
      LOG.warning(
          () ->
              "[breakpoint] %s:%d not armed -- no class around the line, no module owns the file, or it could not be read or parsed"
                  .formatted(uri, breakpoint.line));
      return location;
    }

    location.setClassName(className);
    return location;
  }

  // Every line of one file in one request to its module's worker, which reads the file once. A
  // lookup that fails leaves the file's breakpoints unarmed, not the request failed.
  private Map<Integer, String> classNamesAt(final String uri, final List<Integer> lines) {
    return frames
        .onWorker(
            LatheUri.toPath(uri),
            worker -> worker.submit(session -> session.files().enclosingBinaryNames(uri, lines)))
        .handle(
            (names, error) -> {
              if (error != null) {
                LOG.log(Level.WARNING, error, () -> "[breakpoint] %s lookup failed".formatted(uri));
                return Map.<Integer, String>of();
              }

              if (names.isEmpty()) {
                LOG.fine(() -> "[breakpoint] %s in no module".formatted(uri));
              }

              return names.orElse(Map.of());
            })
        .join();
  }

  private String sourceUri(final String fullyQualifiedName) {
    return TypeSourceLocator.findSourceFile(fullyQualifiedName, frames.sourceRoots())
        .map(LatheUri::fromPath)
        .orElse(null);
  }

  private static String toUri(final String pathOrUri) {
    return LatheUri.fromPath(toPath(pathOrUri));
  }

  private static Path toPath(final String pathOrUri) {
    return pathOrUri.startsWith("file:") ? LatheUri.toPath(pathOrUri) : Path.of(pathOrUri);
  }
}
