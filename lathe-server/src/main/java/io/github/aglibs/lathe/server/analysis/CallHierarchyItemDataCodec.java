package io.github.aglibs.lathe.server.analysis;

import com.google.gson.JsonObject;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;

public final class CallHierarchyItemDataCodec {

  private CallHierarchyItemDataCodec() {}

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
    final var displayName = SourceLocator.declarationName(element).toString();
    final var ownerBinaryName = elements.getBinaryName(owner).toString();
    final var symbolKind =
        kind == ElementKind.CONSTRUCTOR ? SymbolKind.Constructor : SymbolKind.Function;
    final ReferenceTarget target = ReferenceTarget.from(element, types, elements);
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
                target.scope())));
    return item;
  }

  public static JsonObject encode(final CallHierarchyItemData data) {
    final var json = new JsonObject();
    json.addProperty("ownerBinaryName", data.ownerBinaryName());
    json.addProperty("methodName", data.methodName());
    json.addProperty("erasedDescriptor", data.erasedDescriptor());
    json.addProperty("kind", data.kind().name());
    json.addProperty("routingUri", data.routingUri());
    json.addProperty("scope", data.scope().name());
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
          ReferenceTarget.SearchScope.valueOf(json.get("scope").getAsString()));
    }

    return null;
  }
}
