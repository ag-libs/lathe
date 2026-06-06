package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
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
  public Void visitImport(final ImportTree node, final Void ignored) {
    final var qualId = node.getQualifiedIdentifier();
    final var qualIdPath = new TreePath(getCurrentPath(), qualId);
    // For regular imports: resolve directly — avoids false positives at intermediate package
    // segments that SourceLocator.elementAt would produce by walking up through PACKAGE elements.
    // For static imports: elementAt is needed to resolve the member through the enclosing type.
    final var element =
        node.isStatic() ? SourceLocator.elementAt(trees, qualIdPath) : trees.getElement(qualIdPath);
    if (target.matches(element, types, elements)) {
      if (qualId instanceof final MemberSelectTree mst) {
        addLocationAtIdentifier(qualId, mst.getIdentifier().toString());
      } else if (qualId instanceof final IdentifierTree it) {
        addLocation(positions.getStartPosition(cu, qualId), it.getName().length());
      }
    }
    return null; // don't recurse — prevents visitMemberSelect from seeing import segments
  }

  @Override
  public Void visitMemberSelect(final MemberSelectTree node, final Void ignored) {
    scan(node.getExpression(), null);
    if (target.matches(SourceLocator.elementAt(trees, getCurrentPath()), types, elements)) {
      addLocationAtIdentifier(node, node.getIdentifier().toString());
    }
    return null;
  }

  @Override
  public Void visitMethod(final MethodTree node, final Void ignored) {
    if (includeDeclaration) {
      final var element = matchedElement();
      if (element != null) {
        addDeclarationLocation(node, SourceLocator.declarationName(element).toString());
      }
    }
    return super.visitMethod(node, ignored);
  }

  @Override
  public Void visitVariable(final VariableTree node, final Void ignored) {
    if (includeDeclaration && matchedElement() != null) {
      addDeclarationLocation(node, node.getName().toString());
    }
    return super.visitVariable(node, ignored);
  }

  @Override
  public Void visitClass(final ClassTree node, final Void ignored) {
    if (includeDeclaration) {
      final var name = node.getSimpleName().toString();
      if (!name.isEmpty() && matchedElement() != null) {
        addDeclarationLocation(node, name);
      }
    }
    return super.visitClass(node, ignored);
  }

  private Element matchedElement() {
    final var element = trees.getElement(getCurrentPath());
    return target.matches(element, types, elements) ? element : null;
  }

  private void addLocationAtIdentifier(final Tree node, final String name) {
    final long endPos = positions.getEndPosition(cu, node);
    final long nameStart = endPos - name.length();
    if (endPos >= 0 && nameStart >= 0) {
      addLocation(nameStart, name.length());
    }
  }

  private void addDeclarationLocation(final Tree node, final String name) {
    final long namePos =
        SourceLocator.findIdentifierFrom(content, positions.getStartPosition(cu, node), name);
    if (namePos >= 0) {
      addLocation(namePos, name.length());
    }
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
