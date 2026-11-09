package io.github.aglibs.lathe.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;

public final class CallHierarchyItemDataCodec {

  private CallHierarchyItemDataCodec() {}

  // The item for a method or constructor declared in an attributed file: its whole declaration as
  // the range, its name as the selection (the declaration's start when the name is not found).
  static CallHierarchyItem itemAt(
      final Element element,
      final String uri,
      final TreePath declaration,
      final Trees trees,
      final Types types,
      final Elements elements) {
    final CompilationUnitTree cu = declaration.getCompilationUnit();
    final var positions = trees.getSourcePositions();
    final var rangeStart =
        Positions.offsetToPosition(cu, positions.getStartPosition(cu, declaration.getLeaf()));
    final var rangeEnd =
        Positions.offsetToPosition(cu, positions.getEndPosition(cu, declaration.getLeaf()));
    final var displayName = DeclarationNames.declarationName(element).toString();
    Position nameStart;
    try {
      nameStart =
          DeclarationNames.declarationNamePosition(trees, cu, declaration, displayName)
              .orElse(rangeStart);
    } catch (final IOException e) {
      nameStart = rangeStart;
    }

    return buildItem(
        element,
        uri,
        new Range(rangeStart, rangeEnd),
        Positions.nameRange(nameStart, displayName),
        types,
        elements);
  }

  // The item for a method or constructor in a source file outside the analysis (a dependency's):
  // a point at its parsed name position. Empty when no root holds its source.
  static Optional<CallHierarchyItem> itemFromSource(
      final Element element,
      final List<Path> sourceRoots,
      final DefinitionLocator definitionLocator,
      final Types types,
      final Elements elements) {
    return DefinitionLocator.findSourceFile(element, sourceRoots)
        .map(
            file -> {
              final var pos =
                  definitionLocator.parsePosition(file, element).orElse(new Position(0, 0));
              final var point = new Range(pos, pos);
              return buildItem(element, file.toUri().toString(), point, point, types, elements);
            });
  }

  static CallHierarchyItem buildItem(
      final Element element,
      final String uri,
      final Range range,
      final Range selectionRange,
      final Types types,
      final Elements elements) {
    final var kind = element.getKind();
    final var ee = (ExecutableElement) element;
    final var owner = (TypeElement) ee.getEnclosingElement();
    final var displayName = DeclarationNames.declarationName(element).toString();
    final var ownerBinaryName = elements.getBinaryName(owner).toString();
    final var symbolKind =
        kind == ElementKind.CONSTRUCTOR ? SymbolKind.Constructor : SymbolKind.Function;
    final ReferenceTarget target = ReferenceTarget.from(element, types, elements);
    final List<CallHierarchyItemData.Overridden> overridden =
        kind == ElementKind.METHOD
            ? ReferenceTarget.overriddenMethods(ee, owner, types, elements).stream()
                .map(
                    method ->
                        new CallHierarchyItemData.Overridden(
                            elements
                                .getBinaryName((TypeElement) method.getEnclosingElement())
                                .toString(),
                            ReferenceTarget.buildDescriptor(method, types)))
                .toList()
            : List.of();
    final var item = new CallHierarchyItem(displayName, symbolKind, uri, range, selectionRange);
    item.setDetail(ownerBinaryName);
    item.setData(
        encode(
            new CallHierarchyItemData(
                ownerBinaryName,
                target.simpleName(),
                target.erasedDescriptor(),
                kind,
                uri,
                target.scope(),
                target.staticMember(),
                overridden,
                overridden.isEmpty() ? List.of() : typeAndSupertypes(owner, types, elements))));
    return item;
  }

  // A class and every supertype of it, by binary name.
  private static List<String> typeAndSupertypes(
      final TypeElement type, final Types types, final Elements elements) {
    final Set<String> names = new LinkedHashSet<>();
    final var pending = new ArrayDeque<TypeMirror>(List.of(type.asType()));
    while (!pending.isEmpty()) {
      if (types.asElement(pending.removeFirst()) instanceof final TypeElement next
          && names.add(elements.getBinaryName(next).toString())) {
        pending.addAll(types.directSupertypes(next.asType()));
      }
    }

    return List.copyOf(names);
  }

  public static JsonObject encode(final CallHierarchyItemData data) {
    final var json = new JsonObject();
    json.addProperty("ownerBinaryName", data.ownerBinaryName());
    json.addProperty("methodName", data.methodName());
    json.addProperty("erasedDescriptor", data.erasedDescriptor());
    json.addProperty("kind", data.kind().name());
    json.addProperty("routingUri", data.routingUri());
    json.addProperty("scope", data.scope().name());
    json.addProperty("staticMember", data.staticMember());
    final var overridden = new JsonArray();
    data.overridden()
        .forEach(
            method -> {
              final var entry = new JsonObject();
              entry.addProperty("owner", method.ownerBinaryName());
              entry.addProperty("descriptor", method.erasedDescriptor());
              overridden.add(entry);
            });
    json.add("overridden", overridden);
    final var receiverTypes = new JsonArray();
    data.receiverTypes().forEach(receiverTypes::add);
    json.add("receiverTypes", receiverTypes);
    return json;
  }

  public static CallHierarchyItemData decode(final Object data) {
    if (data instanceof final CallHierarchyItemData itemData) {
      return itemData;
    }

    if (data instanceof final JsonObject json) {
      return new CallHierarchyItemData(
          json.get("ownerBinaryName").getAsString(),
          json.get("methodName").getAsString(),
          json.get("erasedDescriptor").getAsString(),
          ElementKind.valueOf(json.get("kind").getAsString()),
          json.get("routingUri").getAsString(),
          ReferenceTarget.SearchScope.valueOf(json.get("scope").getAsString()),
          // An item from before the flag (a server restart under an open hierarchy): not static,
          // which searches every file spelling the name, as then.
          json.has("staticMember") && json.get("staticMember").getAsBoolean(),
          // An item from before the lists: no search through overridden methods upstream, as then.
          json.has("overridden")
              ? json.getAsJsonArray("overridden").asList().stream()
                  .map(JsonElement::getAsJsonObject)
                  .map(
                      entry ->
                          new CallHierarchyItemData.Overridden(
                              entry.get("owner").getAsString(),
                              entry.get("descriptor").getAsString()))
                  .toList()
              : List.of(),
          json.has("receiverTypes")
              ? json.getAsJsonArray("receiverTypes").asList().stream()
                  .map(JsonElement::getAsString)
                  .toList()
              : List.of());
    }

    return null;
  }
}
