package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

final class ReferenceLocator extends SourceTreeLocator {

  private final String uri;
  private final boolean includeDeclaration;
  private final ExecutableElement targetMethod;
  private final List<ReferenceMatch> results = new ArrayList<>();

  private ReferenceLocator(
      final Trees trees,
      final CompilationUnitTree cu,
      final String content,
      final ReferenceTarget target,
      final Types types,
      final Elements elements,
      final String uri,
      final boolean includeDeclaration,
      final ExecutableElement targetMethod) {
    super(trees, cu, content, target, types, elements);
    this.uri = uri;
    this.includeDeclaration = includeDeclaration;
    this.targetMethod = targetMethod;
  }

  static List<ReferenceMatch> references(
      final AttributedFileAnalysis analysis,
      final ReferenceTarget target,
      final String uri,
      final boolean includeDeclaration)
      throws IOException {
    if (target == null || analysis == null || analysis.tree() == null) {
      return List.of();
    }

    final var content = sourceContent(analysis);
    final ExecutableElement targetMethod =
        target.resolveMethodElement(analysis.elements(), analysis.types());
    final var locator =
        new ReferenceLocator(
            analysis.trees(),
            analysis.tree(),
            content,
            target,
            analysis.types(),
            analysis.elements(),
            uri,
            includeDeclaration,
            targetMethod);
    locator.scan(analysis.tree(), null);
    return List.copyOf(locator.results);
  }

  @Override
  public Void visitIdentifier(final IdentifierTree node, final Void ignored) {
    final var name = node.getName().toString();
    if (!name.equals("this") && !name.equals("super")) {
      final var element = trees.getElement(getCurrentPath());
      if (target.matchesWithOverrides(element, types, elements, targetMethod)) {
        addMatch(positions.getStartPosition(cu, node), name.length(), roleForElement(element));
      }
    }

    return super.visitIdentifier(node, ignored);
  }

  @Override
  public Void visitMemberReference(final MemberReferenceTree node, final Void ignored) {
    scan(node.getQualifierExpression(), null);
    final var element = trees.getElement(getCurrentPath());
    if (target.matchesWithOverrides(element, types, elements, targetMethod)) {
      addMatchAtIdentifier(node, node.getName().toString(), ReferenceRole.INVOCATION);
    }
    return null;
  }

  @Override
  public Void visitNewClass(final NewClassTree node, final Void ignored) {
    final var element = trees.getElement(getCurrentPath());
    if (target.matchesWithOverrides(element, types, elements, targetMethod)) {
      final var id = node.getIdentifier();
      final String name =
          id instanceof final MemberSelectTree mst
              ? mst.getIdentifier().toString()
              : id instanceof final IdentifierTree it ? it.getName().toString() : null;
      if (name != null) {
        addMatchAtIdentifier(id, name, ReferenceRole.INVOCATION);
      }
    }
    return super.visitNewClass(node, ignored);
  }

  @Override
  public Void visitImport(final ImportTree node, final Void ignored) {
    final var qualId = node.getQualifiedIdentifier();
    final var qualIdPath = new TreePath(getCurrentPath(), qualId);
    final var element =
        node.isStatic() ? SourceLocator.elementAt(trees, qualIdPath) : trees.getElement(qualIdPath);
    if (target.matchesWithOverrides(element, types, elements, targetMethod)) {
      if (qualId instanceof final MemberSelectTree mst) {
        addMatchAtIdentifier(qualId, mst.getIdentifier().toString(), ReferenceRole.IMPORT);
      } else if (qualId instanceof final IdentifierTree it) {
        addMatch(
            positions.getStartPosition(cu, qualId), it.getName().length(), ReferenceRole.IMPORT);
      }
    }
    return null;
  }

  @Override
  public Void visitMemberSelect(final MemberSelectTree node, final Void ignored) {
    scan(node.getExpression(), null);
    final var element = SourceLocator.elementAt(trees, getCurrentPath());
    if (target.matchesWithOverrides(element, types, elements, targetMethod)) {
      addMatchAtIdentifier(node, node.getIdentifier().toString(), roleForMemberSelect(element));
    }
    return null;
  }

  @Override
  public Void visitMethod(final MethodTree node, final Void ignored) {
    if (includeDeclaration) {
      final var element = matchedElement();
      if (element != null) {
        addDeclarationMatch(node, SourceLocator.declarationName(element).toString());
      }
    }
    return super.visitMethod(node, ignored);
  }

  @Override
  public Void visitVariable(final VariableTree node, final Void ignored) {
    if (includeDeclaration && matchedElement() != null) {
      addDeclarationMatch(node, node.getName().toString());
    }
    return super.visitVariable(node, ignored);
  }

  @Override
  public Void visitClass(final ClassTree node, final Void ignored) {
    if (includeDeclaration) {
      final var name = node.getSimpleName().toString();
      if (!name.isEmpty() && matchedElement() != null) {
        addDeclarationMatch(node, name);
      }
    }
    return super.visitClass(node, ignored);
  }

  private Element matchedElement() {
    final var element = trees.getElement(getCurrentPath());
    return target.matchesWithOverrides(element, types, elements, targetMethod) ? element : null;
  }

  private ReferenceRole roleForElement(final Element element) {
    if (element == null) {
      return ReferenceRole.READ;
    }

    final var kind = element.getKind();
    if (kind == ElementKind.CLASS
        || kind == ElementKind.INTERFACE
        || kind == ElementKind.ENUM
        || kind == ElementKind.RECORD
        || kind == ElementKind.ANNOTATION_TYPE
        || kind == ElementKind.TYPE_PARAMETER) {
      return ReferenceRole.TYPE_USE;
    }

    if (kind == ElementKind.METHOD || kind == ElementKind.CONSTRUCTOR) {
      return ReferenceRole.INVOCATION;
    }

    return isWriteContext() ? ReferenceRole.WRITE : ReferenceRole.READ;
  }

  private ReferenceRole roleForMemberSelect(final Element element) {
    if (element == null) {
      return ReferenceRole.READ;
    }

    final var kind = element.getKind();
    if (kind == ElementKind.CLASS
        || kind == ElementKind.INTERFACE
        || kind == ElementKind.ENUM
        || kind == ElementKind.RECORD
        || kind == ElementKind.ANNOTATION_TYPE
        || kind == ElementKind.TYPE_PARAMETER) {
      return ReferenceRole.TYPE_USE;
    }

    if (kind == ElementKind.METHOD || kind == ElementKind.CONSTRUCTOR) {
      final var parent = getCurrentPath().getParentPath();
      if (parent != null
          && parent.getLeaf() instanceof final MethodInvocationTree inv
          && inv.getMethodSelect() == getCurrentPath().getLeaf()) {
        return ReferenceRole.INVOCATION;
      }
    }

    return isWriteContext() ? ReferenceRole.WRITE : ReferenceRole.READ;
  }

  private boolean isWriteContext() {
    final var parent = getCurrentPath().getParentPath();
    if (parent == null) {
      return false;
    }

    final var leaf = parent.getLeaf();
    if (leaf instanceof final AssignmentTree asgn) {
      return asgn.getVariable() == getCurrentPath().getLeaf();
    }

    if (leaf instanceof final CompoundAssignmentTree casgn) {
      return casgn.getVariable() == getCurrentPath().getLeaf();
    }

    if (leaf instanceof final UnaryTree unary) {
      final var kind = unary.getKind();
      return kind == Tree.Kind.PREFIX_INCREMENT
          || kind == Tree.Kind.PREFIX_DECREMENT
          || kind == Tree.Kind.POSTFIX_INCREMENT
          || kind == Tree.Kind.POSTFIX_DECREMENT;
    }

    return false;
  }

  private void addMatchAtIdentifier(final Tree node, final String name, final ReferenceRole role) {
    // Locate the selector by text from a reliable anchor instead of end-minus-name.length(): a
    // node's end position can be approximate in generated sources, slicing the range onto a
    // neighbouring identifier (FR-010). Anchoring on the receiver end also avoids matching an
    // earlier occurrence of the name inside the receiver (e.g. `amount.amount`).
    final long anchor = identifierAnchor(node);
    if (anchor < 0) {
      return;
    }

    final long nameStart = SourceLocator.findIdentifierFrom(content, anchor, name);
    if (nameStart >= 0) {
      addMatch(nameStart, name.length(), role);
    }
  }

  private long identifierAnchor(final Tree node) {
    if (node instanceof final MemberSelectTree ms) {
      return positions.getEndPosition(cu, ms.getExpression());
    }

    if (node instanceof final MemberReferenceTree mr) {
      return positions.getEndPosition(cu, mr.getQualifierExpression());
    }

    return positions.getStartPosition(cu, node);
  }

  private void addDeclarationMatch(final Tree node, final String name) {
    final long namePos =
        SourceLocator.findIdentifierFrom(content, positions.getStartPosition(cu, node), name);
    if (namePos >= 0) {
      addMatch(namePos, name.length(), ReferenceRole.DECLARATION);
    }
  }

  private void addMatch(final long startOffset, final int nameLength, final ReferenceRole role) {
    if (startOffset < 0 || nameLength <= 0) {
      return;
    }

    final var start = SourceLocator.offsetToPosition(cu, startOffset);
    final var range =
        new Range(start, new Position(start.getLine(), start.getCharacter() + nameLength));

    // A record component's backing field and its canonical-constructor parameter share the header
    // range, so the same identifier can be reached twice; never report one source range twice.
    if (results.stream().anyMatch(match -> match.range().equals(range))) {
      return;
    }

    results.add(new ReferenceMatch(uri, range, role));
  }
}
