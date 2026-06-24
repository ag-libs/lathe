package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import javax.lang.model.element.ElementKind;

public record CallHierarchyItemData(
    String ownerBinaryName,
    String methodName,
    String erasedDescriptor,
    ElementKind kind,
    String routingUri,
    ReferenceTarget.SearchScope scope) {

  public CallHierarchyItemData {
    ValidCheck.check()
        .notBlank(ownerBinaryName, "ownerBinaryName")
        .notBlank(methodName, "methodName")
        .notBlank(erasedDescriptor, "erasedDescriptor")
        .notNull(kind, "kind")
        .notBlank(routingUri, "routingUri")
        .notNull(scope, "scope")
        .validate();
  }
}
