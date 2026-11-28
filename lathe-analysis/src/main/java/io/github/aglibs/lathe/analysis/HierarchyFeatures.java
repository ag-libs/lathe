package io.github.aglibs.lathe.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.util.TreePath;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.TypeHierarchyItem;

// The type and call hierarchies around a cursor: a type's supertypes and subtypes (one level, or
// explored transitively), and a method's call-hierarchy item and outgoing calls. Reads the
// session's cursor and analysis; keeps no state.
public final class HierarchyFeatures {

  private static final Logger LOG = Logger.getLogger(HierarchyFeatures.class.getName());

  private final SourceAnalysisSession session;
  private final DefinitionLocator definitionLocator;
  private final TypeHierarchyResolver typeHierarchyResolver;

  HierarchyFeatures(
      final SourceAnalysisSession session,
      final DefinitionLocator definitionLocator,
      final TypeHierarchyResolver typeHierarchyResolver) {
    this.session = session;
    this.definitionLocator = definitionLocator;
    this.typeHierarchyResolver = typeHierarchyResolver;
  }

  public List<TypeHierarchyItem> prepareTypeHierarchy(
      final SourceFeatureRequest request, final WorkspaceTypeIndex typeIndex) {
    final Optional<Cursor> at = session.cursor(request);
    if (at.isEmpty() || at.get().path() == null) {
      return List.of();
    }

    final Cursor cur = at.get();
    final var trees = cur.analysis().trees();
    // A package segment of a qualified name is about the package, which has no hierarchy, not the
    // class around it (EG-124).
    final Element atCursor = trees.getElement(cur.path());
    if (atCursor != null && atCursor.getKind() == ElementKind.PACKAGE) {
      return List.of();
    }

    // Off a type name, as jdtls answers: on a member's own name its declaring class, else the class
    // around the position. Anonymous and local classes are not in the index, so the class around
    // one stands for it, and Object stands for no member's class: its hierarchy is the whole index.
    final Stream<Element> named =
        Stream.ofNullable(CursorElements.elementAt(trees, cur.path()))
            .filter(TypeElement.class::isInstance);
    final Stream<Element> declaring =
        Stream.ofNullable(atCursor)
            .filter(member -> !(member instanceof TypeElement))
            .map(Element::getEnclosingElement)
            .filter(
                owner ->
                    !(owner instanceof final TypeElement type
                        && type.getQualifiedName().contentEquals("java.lang.Object")));
    final Stream<Element> enclosing =
        Stream.iterate(cur.path(), Objects::nonNull, TreePath::getParentPath)
            .filter(path -> path.getLeaf() instanceof ClassTree)
            .map(trees::getElement);
    return Stream.of(named, declaring, enclosing)
        .flatMap(candidates -> candidates)
        .filter(TypeElement.class::isInstance)
        .map(TypeElement.class::cast)
        .filter(
            type ->
                type.getNestingKind() != NestingKind.ANONYMOUS
                    && type.getNestingKind() != NestingKind.LOCAL)
        .findFirst()
        .map(
            type ->
                typeHierarchyResolver.prepare(
                    cur.analysis().elements().getBinaryName(type).toString(),
                    typeIndex,
                    request.typeSourceRoots()))
        .orElse(List.of());
  }

  public List<CallHierarchyItem> prepareCallHierarchy(final SourceFeatureRequest request) {
    final Optional<Cursor> at = session.cursor(request);
    if (at.isEmpty()) {
      return List.of();
    }

    final Cursor cur = at.get();
    final var element = CursorElements.elementAt(cur.analysis().trees(), cur.path());
    if (element == null) {
      return List.of();
    }

    final var kind = element.getKind();
    if (kind != ElementKind.METHOD && kind != ElementKind.CONSTRUCTOR) {
      return List.of();
    }

    final TreePath methodPath = cur.analysis().trees().getPath(element);
    if (methodPath != null) {
      return List.of(
          CallHierarchyItemDataCodec.itemAt(
              element,
              request.uri(),
              methodPath,
              cur.analysis().trees(),
              cur.analysis().types(),
              cur.analysis().elements()));
    }

    return CallHierarchyItemDataCodec.itemFromSource(
            element,
            request.allSourceRoots(),
            definitionLocator,
            cur.analysis().types(),
            cur.analysis().elements())
        .map(List::of)
        .orElseGet(List::of);
  }

  public List<CallHierarchyOutgoingCall> outgoingCalls(
      final CallHierarchyItem item,
      final String uri,
      final String content,
      final int version,
      final List<Path> sourceRoots) {
    final CallHierarchyItemData data = CallHierarchyItemDataCodec.decode(item.getData());
    if (data == null) {
      return List.of();
    }

    final var analysis = session.analysis(uri, content, version, () -> {});
    if (analysis == null) {
      return List.of();
    }

    final var target =
        new ReferenceTarget(
            data.kind(),
            data.ownerBinaryName(),
            data.methodName(),
            data.erasedDescriptor(),
            data.scope(),
            List.of(),
            false,
            data.staticMember(),
            data.declarationOffset(),
            null);
    try {
      return CallHierarchyOutgoingLocator.scan(analysis, target, sourceRoots, definitionLocator);
    } catch (final IOException e) {
      LOG.log(
          Level.WARNING, e, () -> "[outgoingCalls] failed to read source for %s".formatted(uri));
      return List.of();
    }
  }

  public List<TypeHierarchyItem> typeHierarchySupertypes(
      final TypeHierarchyItem item,
      final WorkspaceTypeIndex typeIndex,
      final List<Path> sourceRoots) {
    return typeHierarchyResolver.supertypes(item, typeIndex, sourceRoots);
  }

  public List<TypeHierarchyItem> typeHierarchySubtypes(
      final TypeHierarchyItem item,
      final WorkspaceTypeIndex typeIndex,
      final List<Path> sourceRoots) {
    return typeHierarchyResolver.subtypes(item, typeIndex, sourceRoots);
  }

  public TypeHierarchyExplorerResult typeHierarchyExplore(
      final TypeHierarchyItem item,
      final WorkspaceTypeIndex typeIndex,
      final List<Path> sourceRoots,
      final int nodeCap) {
    return typeHierarchyResolver.explore(item, typeIndex, sourceRoots, nodeCap);
  }
}
