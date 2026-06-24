package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

final class CallHierarchyIncomingLocator extends TreePathScanner<Void, Void> {

  private final Map<Element, TreePath> callerPaths = new LinkedHashMap<>();
  private final Map<Element, List<Range>> callerRanges = new LinkedHashMap<>();

  private final Trees trees;
  private final CompilationUnitTree cu;
  private final String content;
  private final SourcePositions positions;
  private final ReferenceTarget target;
  private final Types types;
  private final Elements elements;

  private CallHierarchyIncomingLocator(
      final Trees trees,
      final CompilationUnitTree cu,
      final String content,
      final ReferenceTarget target,
      final Types types,
      final Elements elements) {
    this.trees = trees;
    this.cu = cu;
    this.content = content;
    this.positions = trees.getSourcePositions();
    this.target = target;
    this.types = types;
    this.elements = elements;
  }

  static List<CallHierarchyIncomingCall> scan(
      final AttributedFileAnalysis analysis, final ReferenceTarget target, final String fileUri)
      throws IOException {
    final var content = analysis.tree().getSourceFile().getCharContent(false).toString();
    final var locator =
        new CallHierarchyIncomingLocator(
            analysis.trees(),
            analysis.tree(),
            content,
            target,
            analysis.types(),
            analysis.elements());
    locator.scan(analysis.tree(), null);
    return locator.buildResults(fileUri);
  }

  @Override
  public Void visitImport(final ImportTree node, final Void ignored) {
    return null;
  }

  @Override
  public Void visitIdentifier(final IdentifierTree node, final Void ignored) {
    final var name = node.getName().toString();
    if (!name.equals("this") && !name.equals("super")) {
      final var element = trees.getElement(getCurrentPath());
      if (target.matches(element, types, elements)) {
        final var parent = getCurrentPath().getParentPath().getLeaf();
        if (!(parent instanceof MethodTree)
            && !(parent instanceof VariableTree)
            && !(parent instanceof ClassTree)) {
          final long startOff = positions.getStartPosition(cu, node);
          addCallSite(rangeFor(startOff, name));
        }
      }
    }
    return super.visitIdentifier(node, ignored);
  }

  @Override
  public Void visitMemberSelect(final MemberSelectTree node, final Void ignored) {
    scan(node.getExpression(), null);
    final var element = SourceLocator.elementAt(trees, getCurrentPath());
    if (target.matches(element, types, elements)) {
      final var parent = getCurrentPath().getParentPath().getLeaf();
      if (parent instanceof final MethodInvocationTree inv && inv.getMethodSelect() == node) {
        final String idName = node.getIdentifier().toString();
        final long startOff =
            SourceLocator.findIdentifierFrom(content, positions.getStartPosition(cu, node), idName);
        if (startOff >= 0) {
          addCallSite(rangeFor(startOff, idName));
        }
      }
    }
    return null;
  }

  @Override
  public Void visitNewClass(final NewClassTree node, final Void ignored) {
    final var element = trees.getElement(getCurrentPath());
    if (target.matches(element, types, elements)) {
      final var id = node.getIdentifier();
      final String name =
          id instanceof final MemberSelectTree mst
              ? mst.getIdentifier().toString()
              : id instanceof final IdentifierTree it ? it.getName().toString() : null;
      if (name != null) {
        final long startOff =
            SourceLocator.findIdentifierFrom(content, positions.getStartPosition(cu, id), name);
        if (startOff >= 0) {
          addCallSite(rangeFor(startOff, name));
        }
      }
    }
    return super.visitNewClass(node, ignored);
  }

  @Override
  public Void visitMemberReference(final MemberReferenceTree node, final Void ignored) {
    scan(node.getQualifierExpression(), null);
    final var element = trees.getElement(getCurrentPath());
    if (target.matches(element, types, elements)) {
      final String name = node.getName().toString();
      final long startOff =
          SourceLocator.findIdentifierFrom(content, positions.getStartPosition(cu, node), name);
      if (startOff >= 0) {
        addCallSite(rangeFor(startOff, name));
      }
    }
    return null;
  }

  private void addCallSite(final Range callSite) {
    final TreePath callerPath = enclosingMethod(getCurrentPath());
    if (callerPath == null) {
      return;
    }
    final var callerElement = trees.getElement(callerPath);
    if (callerElement == null) {
      return;
    }
    callerPaths.putIfAbsent(callerElement, callerPath);
    callerRanges.computeIfAbsent(callerElement, k -> new ArrayList<>()).add(callSite);
  }

  private static TreePath enclosingMethod(TreePath path) {
    path = path.getParentPath();
    while (path != null) {
      if (path.getLeaf() instanceof MethodTree) {
        return path;
      }
      if (path.getLeaf() instanceof ClassTree) {
        return null;
      }
      path = path.getParentPath();
    }
    return null;
  }

  private Range rangeFor(final long startOff, final String name) {
    final var start = SourceLocator.offsetToPosition(cu, startOff);
    return new Range(start, new Position(start.getLine(), start.getCharacter() + name.length()));
  }

  private List<CallHierarchyIncomingCall> buildResults(final String fileUri) {
    final var results = new ArrayList<CallHierarchyIncomingCall>();
    for (final var callerElement : callerPaths.keySet()) {
      final var methodPath = callerPaths.get(callerElement);
      final var ranges = callerRanges.get(callerElement);
      final var callerItem = buildCallerItem(callerElement, methodPath, fileUri);
      if (callerItem != null) {
        results.add(new CallHierarchyIncomingCall(callerItem, List.copyOf(ranges)));
      }
    }
    return List.copyOf(results);
  }

  private CallHierarchyItem buildCallerItem(
      final Element callerElement, final TreePath methodPath, final String fileUri) {
    final var kind = callerElement.getKind();
    if (kind != ElementKind.METHOD && kind != ElementKind.CONSTRUCTOR) {
      return null;
    }
    final String displayName = SourceLocator.declarationName(callerElement).toString();
    final long startOff = positions.getStartPosition(cu, methodPath.getLeaf());
    final long endOff = positions.getEndPosition(cu, methodPath.getLeaf());
    final var rangeStart = SourceLocator.offsetToPosition(cu, startOff);
    final var rangeEnd = SourceLocator.offsetToPosition(cu, endOff);
    final var range = new Range(rangeStart, rangeEnd);
    Position selStart;
    try {
      selStart =
          SourceLocator.declarationNamePosition(trees, cu, methodPath, displayName)
              .orElse(rangeStart);
    } catch (final IOException e) {
      selStart = rangeStart;
    }
    final var selEnd =
        new Position(selStart.getLine(), selStart.getCharacter() + displayName.length());
    return CallHierarchyItemDataCodec.buildItem(
        callerElement, fileUri, range, new Range(selStart, selEnd), types, elements);
  }
}
