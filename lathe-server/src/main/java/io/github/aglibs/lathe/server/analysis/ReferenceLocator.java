package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

final class ReferenceLocator extends TreePathScanner<Void, Void> {

  private final Trees trees;
  private final CompilationUnitTree cu;
  private final SourcePositions positions;
  private final String content;
  private final ReferenceTarget target;
  private final Types types;
  private final Elements elements;
  private final String uri;
  private final boolean includeDeclaration;
  private final List<Location> results = new ArrayList<>();

  private ReferenceLocator(
      final Trees trees,
      final CompilationUnitTree cu,
      final String content,
      final ReferenceTarget target,
      final Types types,
      final Elements elements,
      final String uri,
      final boolean includeDeclaration) {
    this.trees = trees;
    this.cu = cu;
    this.positions = trees.getSourcePositions();
    this.content = content;
    this.target = target;
    this.types = types;
    this.elements = elements;
    this.uri = uri;
    this.includeDeclaration = includeDeclaration;
  }

  static List<Location> references(
      final AttributedFileAnalysis analysis,
      final ReferenceTarget target,
      final String uri,
      final boolean includeDeclaration)
      throws IOException {
    if (target == null || analysis == null || analysis.tree() == null) {
      return List.of();
    }

    final var content = analysis.tree().getSourceFile().getCharContent(true).toString();
    final var locator =
        new ReferenceLocator(
            analysis.trees(),
            analysis.tree(),
            content,
            target,
            analysis.types(),
            analysis.elements(),
            uri,
            includeDeclaration);
    locator.scan(analysis.tree(), null);
    return List.copyOf(locator.results);
  }

  @Override
  public Void visitIdentifier(final IdentifierTree node, final Void ignored) {
    final var name = node.getName().toString();
    if (!name.equals("this") && !name.equals("super")) {
      final var element = trees.getElement(getCurrentPath());
      if (target.matches(element, types, elements)) {
        addLocation(positions.getStartPosition(cu, node), name.length());
      }
    }

    return super.visitIdentifier(node, ignored);
  }

  @Override
  public Void visitMemberSelect(final MemberSelectTree node, final Void ignored) {
    scan(node.getExpression(), null);
    final var element = SourceLocator.elementAt(trees, getCurrentPath());
    if (target.matches(element, types, elements)) {
      final long endPos = positions.getEndPosition(cu, node);
      final var name = node.getIdentifier().toString();
      final long nameStart = endPos - name.length();
      if (endPos >= 0 && nameStart >= 0) {
        addLocation(nameStart, name.length());
      }
    }

    return null;
  }

  @Override
  public Void visitMethod(final MethodTree node, final Void ignored) {
    if (includeDeclaration) {
      final var element = trees.getElement(getCurrentPath());
      if (target.matches(element, types, elements)) {
        final var name = SourceLocator.declarationName(element).toString();
        final long namePos =
            SourceLocator.findIdentifierFrom(content, positions.getStartPosition(cu, node), name);
        if (namePos >= 0) {
          addLocation(namePos, name.length());
        }
      }
    }

    return super.visitMethod(node, ignored);
  }

  @Override
  public Void visitVariable(final VariableTree node, final Void ignored) {
    if (includeDeclaration) {
      final var element = trees.getElement(getCurrentPath());
      if (target.matches(element, types, elements)) {
        final var name = node.getName().toString();
        final long namePos =
            SourceLocator.findIdentifierFrom(content, positions.getStartPosition(cu, node), name);
        if (namePos >= 0) {
          addLocation(namePos, name.length());
        }
      }
    }

    return super.visitVariable(node, ignored);
  }

  @Override
  public Void visitClass(final ClassTree node, final Void ignored) {
    if (includeDeclaration) {
      final var name = node.getSimpleName().toString();
      if (!name.isEmpty()) {
        final var element = trees.getElement(getCurrentPath());
        if (target.matches(element, types, elements)) {
          final long namePos =
              SourceLocator.findIdentifierFrom(content, positions.getStartPosition(cu, node), name);
          if (namePos >= 0) {
            addLocation(namePos, name.length());
          }
        }
      }
    }

    return super.visitClass(node, ignored);
  }

  private void addLocation(final long startOffset, final int nameLength) {
    if (startOffset < 0 || nameLength <= 0) {
      return;
    }

    final var start = SourceLocator.offsetToPosition(cu, startOffset);
    results.add(
        new Location(
            uri,
            new Range(start, new Position(start.getLine(), start.getCharacter() + nameLength))));
  }
}
