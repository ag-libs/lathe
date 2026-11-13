package io.github.aglibs.lathe.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.Range;

final class CallHierarchyIncomingLocator extends SourceTreeLocator {

  private final Map<Element, TreePath> callerPaths = new LinkedHashMap<>();
  private final Map<Element, List<Range>> callerRanges = new LinkedHashMap<>();
  private final ExecutableElement targetMethod;
  private final List<ReferenceTarget> overriddenTargets;
  private final Set<String> receiverTypes;

  private CallHierarchyIncomingLocator(
      final Trees trees,
      final CompilationUnitTree cu,
      final String content,
      final ReferenceTarget target,
      final Types types,
      final Elements elements,
      final ExecutableElement targetMethod,
      final CallHierarchyItemData item) {
    super(trees, cu, content, target, types, elements);
    this.targetMethod = targetMethod;
    this.overriddenTargets =
        item.overridden().stream()
            .map(
                method ->
                    ReferenceTarget.ofExecutable(
                        ElementKind.METHOD,
                        method.ownerBinaryName(),
                        item.methodName(),
                        method.erasedDescriptor(),
                        item.scope(),
                        false))
            .toList();
    this.receiverTypes = Set.copyOf(item.receiverTypes());
  }

  static List<CallHierarchyIncomingCall> scan(
      final AttributedFileAnalysis analysis, final CallHierarchyItemData item, final String fileUri)
      throws IOException {
    final var content = sourceContent(analysis);
    final ReferenceTarget target = item.target();
    final ExecutableElement targetMethod =
        target.resolveMethodElement(analysis.elements(), analysis.types());
    final var locator =
        new CallHierarchyIncomingLocator(
            analysis.trees(),
            analysis.tree(),
            content,
            target,
            analysis.types(),
            analysis.elements(),
            targetMethod,
            item);
    locator.scan(analysis.tree(), null);
    return locator.buildResults(fileUri);
  }

  @Override
  public Void visitImport(final ImportTree node, final Void ignored) {
    return null;
  }

  @Override
  public Void visitIdentifier(final IdentifierTree node, final Void ignored) {
    if (positions.getEndPosition(cu, node) < 0) {
      return null;
    }

    final var element = trees.getElement(getCurrentPath());
    if (!callReaches(element, null)) {
      return super.visitIdentifier(node, ignored);
    }

    final var parent = getCurrentPath().getParentPath().getLeaf();
    if (!(parent instanceof MethodTree)
        && !(parent instanceof VariableTree)
        && !(parent instanceof ClassTree)) {
      nameRange(node, node.getName().toString()).ifPresent(this::addCallSite);
    }

    return super.visitIdentifier(node, ignored);
  }

  @Override
  public Void visitMemberSelect(final MemberSelectTree node, final Void ignored) {
    scan(node.getExpression(), null);
    final var element = CursorElements.elementAt(trees, getCurrentPath());
    if (callReaches(element, new TreePath(getCurrentPath(), node.getExpression()))) {
      final var parent = getCurrentPath().getParentPath().getLeaf();
      if (parent instanceof final MethodInvocationTree inv && inv.getMethodSelect() == node) {
        nameRange(node, node.getIdentifier().toString()).ifPresent(this::addCallSite);
      }
    }
    return null;
  }

  @Override
  public Void visitNewClass(final NewClassTree node, final Void ignored) {
    final Element element = CursorElements.newClassConstructor(trees, getCurrentPath());
    if (callReaches(element, null)) {
      final Tree id = CursorElements.newClassType(node);
      final String name =
          id instanceof final MemberSelectTree mst
              ? mst.getIdentifier().toString()
              : id instanceof final IdentifierTree it ? it.getName().toString() : null;
      if (name != null) {
        nameRange(id, name).ifPresent(this::addCallSite);
      }
    }
    return super.visitNewClass(node, ignored);
  }

  @Override
  public Void visitMemberReference(final MemberReferenceTree node, final Void ignored) {
    scan(node.getQualifierExpression(), null);
    final var element = trees.getElement(getCurrentPath());
    if (callReaches(element, new TreePath(getCurrentPath(), node.getQualifierExpression()))) {
      final String name = DeclarationNames.referenceName(node);
      nameRange(node, name).ifPresent(this::addCallSite);
    }
    return null;
  }

  // Whether the element is a call that can reach the method: a call to it or to an override of it,
  // or one through a method it overrides on a receiver that can hold its class. Where the method's
  // class is not on the classpath (an upstream module) it cannot be resolved, so a call through an
  // overridden method is matched by that method's own class and descriptor. `receiver` is null for
  // an unqualified call.
  private boolean callReaches(final Element element, final TreePath receiver) {
    if (target.matchesWithOverrides(element, types, elements, targetMethod)) {
      return !(element instanceof final ExecutableElement method)
          || targetMethod == null
          || method.equals(targetMethod)
          || !elements.overrides(
              targetMethod, method, (TypeElement) targetMethod.getEnclosingElement())
          || receiverHolds(method, receiver);
    }

    return targetMethod == null
        && element instanceof final ExecutableElement method
        && overriddenTargets.stream().anyMatch(other -> other.matches(method, types, elements))
        && receiverHolds(method, receiver);
  }

  // A call through an overridden method reaches the method only on a receiver whose static type
  // can hold the method's class: a supertype or a subtype of it. A super call, plain or qualified
  // (`Iface.super.m()`), never dispatches to an override; a receiver of unknown type (code with
  // errors) is kept. An unqualified call's receiver is the enclosing class inheriting the method.
  private boolean receiverHolds(final ExecutableElement called, final TreePath receiver) {
    if (receiver != null && isSuper(receiver.getLeaf())) {
      return false;
    }

    final TypeMirror receiverType =
        receiver == null ? implicitReceiver(called) : trees.getTypeMirror(receiver);
    if (receiverType == null || types.erasure(receiverType).getKind() != TypeKind.DECLARED) {
      return true;
    }

    final TypeMirror held = types.erasure(receiverType);
    // Upstream the method's class cannot be a subtype of the receiver's, only it a supertype of
    // the method's class, which receiverTypes name.
    if (targetMethod == null) {
      return types.asElement(held) instanceof final TypeElement heldType
          && receiverTypes.contains(elements.getBinaryName(heldType).toString());
    }

    final TypeMirror targetClass = types.erasure(targetMethod.getEnclosingElement().asType());
    return types.isSubtype(targetClass, held) || types.isSubtype(held, targetClass);
  }

  private static boolean isSuper(final Tree receiver) {
    return receiver instanceof final IdentifierTree id && id.getName().contentEquals("super")
        || receiver instanceof final MemberSelectTree select
            && select.getIdentifier().contentEquals("super");
  }

  // The innermost enclosing class that inherits the called method: an unqualified call's `this`.
  private TypeMirror implicitReceiver(final ExecutableElement method) {
    final TypeMirror owner = types.erasure(method.getEnclosingElement().asType());
    for (TreePath path = getCurrentPath(); path != null; path = path.getParentPath()) {
      if (path.getLeaf() instanceof ClassTree
          && trees.getElement(path) instanceof final TypeElement enclosing
          && types.isSubtype(types.erasure(enclosing.asType()), owner)) {
        return enclosing.asType();
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

  private static TreePath enclosingMethod(final TreePath path) {
    return TreePaths.nearest(path.getParentPath(), MethodTree.class, TreePaths::isMemberBoundary);
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
    return CallHierarchyItemDataCodec.itemAt(
        callerElement, fileUri, methodPath, trees, types, elements);
  }
}
