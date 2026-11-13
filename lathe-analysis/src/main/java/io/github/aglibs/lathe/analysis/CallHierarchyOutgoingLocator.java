package io.github.aglibs.lathe.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberReferenceTree.ReferenceMode;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.Range;

final class CallHierarchyOutgoingLocator extends SourceTreeLocator {

  private final Map<Element, List<Range>> callSites = new LinkedHashMap<>();
  // The scan still walks the class's field initializers, enum constants among them (each an
  // implicit constructor call); only calls inside the target method's body are its own.
  private boolean inTarget;

  private CallHierarchyOutgoingLocator(
      final Trees trees,
      final CompilationUnitTree cu,
      final String content,
      final ReferenceTarget target,
      final Types types,
      final Elements elements) {
    super(trees, cu, content, target, types, elements);
  }

  static List<CallHierarchyOutgoingCall> scan(
      final AttributedFileAnalysis analysis,
      final ReferenceTarget target,
      final List<Path> sourceRoots,
      final DefinitionLocator definitionLocator)
      throws IOException {
    final var content = sourceContent(analysis);
    final var locator =
        new CallHierarchyOutgoingLocator(
            analysis.trees(),
            analysis.tree(),
            content,
            target,
            analysis.types(),
            analysis.elements());
    locator.scan(analysis.tree(), null);
    return locator.buildResults(sourceRoots, definitionLocator);
  }

  @Override
  public Void visitMethod(final MethodTree node, final Void ignored) {
    final var element = trees.getElement(getCurrentPath());
    if (target.matches(element, types, elements) && node.getBody() != null) {
      inTarget = true;
      scan(node.getBody(), null);
      inTarget = false;
    }

    return null;
  }

  @Override
  public Void visitMethodInvocation(final MethodInvocationTree node, final Void ignored) {
    final var element = CursorElements.elementAt(trees, getCurrentPath());
    if (element instanceof ExecutableElement
        && positions.getEndPosition(cu, node.getMethodSelect()) >= 0) {
      final var sel = node.getMethodSelect();
      if (sel instanceof final MemberSelectTree mst) {
        final String name = mst.getIdentifier().toString();
        nameRange(mst, name).ifPresent(range -> addCallSite(element, range));
      } else if (sel instanceof final IdentifierTree it) {
        nameRange(it, it.getName().toString()).ifPresent(range -> addCallSite(element, range));
      }
    }
    return super.visitMethodInvocation(node, ignored);
  }

  @Override
  public Void visitNewClass(final NewClassTree node, final Void ignored) {
    final Element element = CursorElements.newClassConstructor(trees, getCurrentPath());
    if (element instanceof ExecutableElement
        && !element.getEnclosingElement().getSimpleName().isEmpty()) {
      final Tree id = CursorElements.newClassType(node);
      final String name =
          id instanceof final MemberSelectTree mst
              ? mst.getIdentifier().toString()
              : id instanceof final IdentifierTree it ? it.getName().toString() : null;
      if (name != null) {
        nameRange(id, name).ifPresent(range -> addCallSite(element, range));
      }
    }
    return super.visitNewClass(node, ignored);
  }

  @Override
  public Void visitMemberReference(final MemberReferenceTree node, final Void ignored) {
    scan(node.getQualifierExpression(), null);
    final var element = trees.getElement(getCurrentPath());
    // `Box::new` calls Box's constructor; an array's (`int[]::new`) has none to list.
    if (element instanceof ExecutableElement && !arrayConstructor(node)) {
      final String name = DeclarationNames.referenceName(node);
      nameRange(node, name).ifPresent(range -> addCallSite(element, range));
    }
    return null;
  }

  private boolean arrayConstructor(final MemberReferenceTree node) {
    if (node.getMode() != ReferenceMode.NEW) {
      return false;
    }

    final TypeMirror qualifier =
        trees.getTypeMirror(new TreePath(getCurrentPath(), node.getQualifierExpression()));
    return qualifier != null && qualifier.getKind() == TypeKind.ARRAY;
  }

  private void addCallSite(final Element element, final Range range) {
    if (!inTarget) {
      return;
    }

    callSites.computeIfAbsent(element, k -> new ArrayList<>()).add(range);
  }

  private List<CallHierarchyOutgoingCall> buildResults(
      final List<Path> sourceRoots, final DefinitionLocator definitionLocator) {
    final var results = new ArrayList<CallHierarchyOutgoingCall>();
    for (final var entry : callSites.entrySet()) {
      final var calleeElement = entry.getKey();
      final var ranges = entry.getValue();
      CallHierarchyItemDataCodec.itemFromSource(
              calleeElement, sourceRoots, definitionLocator, types, elements)
          .ifPresent(
              calleeItem ->
                  results.add(new CallHierarchyOutgoingCall(calleeItem, List.copyOf(ranges))));
    }
    return List.copyOf(results);
  }
}
