package io.github.aglibs.lathe.analysis;

import com.sun.source.util.TreePath;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
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
    if (at.isEmpty()) {
      return List.of();
    }

    final Cursor cur = at.get();
    final var element = SourceLocator.elementAt(cur.analysis().trees(), cur.path());
    if (!(element instanceof final TypeElement typeElement)) {
      return List.of();
    }

    final var binaryName = cur.analysis().elements().getBinaryName(typeElement).toString();
    return typeHierarchyResolver.prepare(binaryName, typeIndex, request.typeSourceRoots());
  }

  public List<CallHierarchyItem> prepareCallHierarchy(final SourceFeatureRequest request) {
    final Optional<Cursor> at = session.cursor(request);
    if (at.isEmpty()) {
      return List.of();
    }

    final Cursor cur = at.get();
    final var element = SourceLocator.elementAt(cur.analysis().trees(), cur.path());
    if (element == null) {
      return List.of();
    }

    final var kind = element.getKind();
    if (kind != ElementKind.METHOD && kind != ElementKind.CONSTRUCTOR) {
      return List.of();
    }

    final var displayName = SourceLocator.declarationName(element).toString();

    final TreePath methodPath = cur.analysis().trees().getPath(element);
    if (methodPath != null) {
      final var cu = cur.analysis().tree();
      final var positions = cur.analysis().trees().getSourcePositions();
      final long startOff = positions.getStartPosition(cu, methodPath.getLeaf());
      final long endOff = positions.getEndPosition(cu, methodPath.getLeaf());
      final var rangeStart = SourceLocator.offsetToPosition(cu, startOff);
      final var rangeEnd = SourceLocator.offsetToPosition(cu, endOff);
      final var range = new Range(rangeStart, rangeEnd);
      Position selStart;
      try {
        selStart =
            SourceLocator.declarationNamePosition(
                    cur.analysis().trees(), cu, methodPath, displayName)
                .orElse(rangeStart);
      } catch (final IOException e) {
        selStart = rangeStart;
      }
      final var selEnd =
          new Position(selStart.getLine(), selStart.getCharacter() + displayName.length());
      final var selectionRange = new Range(selStart, selEnd);
      return List.of(
          CallHierarchyItemDataCodec.buildItem(
              element,
              request.uri(),
              range,
              selectionRange,
              cur.analysis().types(),
              cur.analysis().elements()));
    }

    return DefinitionLocator.findSourceFile(element, request.allSourceRoots())
        .map(
            file -> {
              final var pos =
                  definitionLocator.parsePosition(file, element).orElse(new Position(0, 0));
              final var pointRange = new Range(pos, pos);
              final var uri = file.toUri().toString();
              return CallHierarchyItemDataCodec.buildItem(
                  element,
                  uri,
                  pointRange,
                  pointRange,
                  cur.analysis().types(),
                  cur.analysis().elements());
            })
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
            -1);
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
