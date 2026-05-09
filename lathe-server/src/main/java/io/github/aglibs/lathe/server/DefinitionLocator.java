package io.github.aglibs.lathe.server;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

final class DefinitionLocator {

  private static final Logger LOG = Logger.getLogger(DefinitionLocator.class.getName());

  private DefinitionLocator() {}

  static Optional<Location> locate(
      final Element element,
      final Trees trees,
      final List<Path> sourceRoots,
      final String sourceUri) {
    if (element == null) {
      return Optional.empty();
    }

    final var path = trees.getPath(element);
    if (path != null) {
      final var cu = path.getCompilationUnit();
      final long declStart = trees.getSourcePositions().getStartPosition(cu, path.getLeaf());
      if (declStart != Diagnostic.NOPOS) {
        try {
          final CharSequence content = cu.getSourceFile().getCharContent(false);
          final long nameOffset =
              nameOffset(content, declStart, element.getSimpleName().toString());
          final var lspPos = SourceLocator.offsetToPosition(cu, nameOffset);
          LOG.fine(
              () ->
                  "[definition] same-file %s %d:%d"
                      .formatted(sourceUri, lspPos.getLine(), lspPos.getCharacter()));
          return Optional.of(new Location(sourceUri, new Range(lspPos, lspPos)));
        } catch (final IOException e) {
          LOG.log(
              Level.WARNING,
              e,
              () -> "[definition] failed to read source for %s".formatted(sourceUri));
        }
      }
    }

    final var topLevel = topLevelClass(element);
    if (topLevel == null) {
      return Optional.empty();
    }
    final var simpleName = topLevel.getSimpleName().toString();
    final var fileName = simpleName + ".java";
    for (final var sourceRoot : sourceRoots) {
      try (final var stream = Files.walk(sourceRoot)) {
        final var found =
            stream.filter(p -> p.getFileName().toString().equals(fileName)).findFirst();
        if (found.isPresent()) {
          final var lspPos = parsePosition(found.get(), simpleName);
          LOG.fine(
              () ->
                  "[definition] reactor %s %d:%d"
                      .formatted(found.get(), lspPos.getLine(), lspPos.getCharacter()));
          return Optional.of(
              new Location(found.get().toUri().toString(), new Range(lspPos, lspPos)));
        }
      } catch (final IOException e) {
        LOG.log(Level.WARNING, e, () -> "[definition] error scanning %s".formatted(sourceRoot));
      }
    }

    return Optional.empty();
  }

  static Position parsePosition(final Path sourceFile, final String simpleName) {
    final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    try (final StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
      final JavaFileObject jfo = fm.getJavaFileObjects(sourceFile).iterator().next();
      final var task = (JavacTask) compiler.getTask(null, fm, null, null, null, List.of(jfo));
      final CompilationUnitTree cu = task.parse().iterator().next();
      final Trees parseTrees = Trees.instance(task);
      final SourcePositions positions = parseTrees.getSourcePositions();
      final CharSequence content = jfo.getCharContent(false);
      for (final Tree decl : cu.getTypeDecls()) {
        if (decl instanceof final ClassTree ct && ct.getSimpleName().contentEquals(simpleName)) {
          final long declStart = positions.getStartPosition(cu, decl);
          if (declStart != Diagnostic.NOPOS) {
            return SourceLocator.offsetToPosition(cu, nameOffset(content, declStart, simpleName));
          }
        }
      }
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[definition] failed to parse %s".formatted(sourceFile));
    }
    return new Position(0, 0);
  }

  private static long nameOffset(
      final CharSequence content, final long declStart, final String name) {
    final int idx = content.toString().indexOf(name, (int) declStart);
    return idx >= 0 ? idx : declStart;
  }

  private static TypeElement topLevelClass(final Element element) {
    var e = element;
    while (e != null) {
      if (e instanceof final TypeElement te
          && e.getEnclosingElement() != null
          && e.getEnclosingElement().getKind() == ElementKind.PACKAGE) {
        return te;
      }
      e = e.getEnclosingElement();
    }
    return null;
  }
}
