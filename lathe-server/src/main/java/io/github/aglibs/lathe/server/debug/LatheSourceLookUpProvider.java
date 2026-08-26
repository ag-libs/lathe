package io.github.aglibs.lathe.server.debug;

import com.microsoft.java.debug.core.JavaBreakpointLocation;
import com.microsoft.java.debug.core.adapter.ISourceLookUpProvider;
import com.microsoft.java.debug.core.adapter.Source;
import com.microsoft.java.debug.core.adapter.SourceType;
import com.microsoft.java.debug.core.protocol.Types;
import io.github.aglibs.lathe.server.LatheUri;
import io.github.aglibs.lathe.server.analysis.TypeSourceLocator;
import io.github.aglibs.lathe.server.module.CompilationWorker;
import io.github.aglibs.lathe.server.module.WorkspaceModuleRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Maps between the debug adapter and Lathe's source model: a source line to the enclosing class
 * binary name (so JDI can arm a breakpoint) and a class name back to its source file. Each source
 * URI is routed through the workspace to its owning module's {@link CompilationWorker} — main,
 * test, or an upstream reactor module — reusing the attributed analysis of the open file so nested
 * and anonymous classes resolve to the exact binary name javac assigns. A file belongs to exactly
 * one source tree, so the routing is unambiguous. Class-to-source lookup still spans the launched
 * module's source roots (cross-module reverse lookup is Phase 2); expression support is deferred.
 */
public final class LatheSourceLookUpProvider implements ISourceLookUpProvider {

  private final WorkspaceModuleRegistry workspace;
  private final List<Path> sourceRoots;

  public LatheSourceLookUpProvider(
      final WorkspaceModuleRegistry workspace, final List<Path> sourceRoots) {
    this.workspace = workspace;
    this.sourceRoots = List.copyOf(sourceRoots);
  }

  @Override
  public boolean supportsRealtimeBreakpointVerification() {
    return false;
  }

  @Override
  public JavaBreakpointLocation[] getBreakpointLocations(
      final String sourceUri, final Types.SourceBreakpoint[] breakpoints) {
    final String uri = toUri(sourceUri);
    return Arrays.stream(breakpoints)
        .map(breakpoint -> breakpointLocation(uri, breakpoint))
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
    return Arrays.stream(lines)
        .mapToObj(line -> classNameAt(uri, line).orElse(""))
        .toArray(String[]::new);
  }

  @Override
  @SuppressWarnings("deprecation")
  public String getSourceFileURI(final String fullyQualifiedName, final String sourcePath) {
    return sourceUri(fullyQualifiedName);
  }

  private JavaBreakpointLocation breakpointLocation(
      final String uri, final Types.SourceBreakpoint breakpoint) {
    final var location = new JavaBreakpointLocation(breakpoint.line, breakpoint.column);
    classNameAt(uri, breakpoint.line).ifPresent(location::setClassName);
    return location;
  }

  private Optional<String> classNameAt(final String uri, final int line) {
    return workspace
        .moduleSourceFor(LatheUri.toPath(uri))
        .map(workspace::workerFor)
        .flatMap(worker -> worker.enclosingBinaryName(uri, line).join());
  }

  private String sourceUri(final String fullyQualifiedName) {
    return TypeSourceLocator.findSourceFile(fullyQualifiedName, sourceRoots)
        .map(path -> path.toUri().toString())
        .orElse(null);
  }

  private static String toUri(final String pathOrUri) {
    return toPath(pathOrUri).toUri().toString();
  }

  private static Path toPath(final String pathOrUri) {
    return pathOrUri.startsWith("file:") ? LatheUri.toPath(pathOrUri) : Path.of(pathOrUri);
  }
}
