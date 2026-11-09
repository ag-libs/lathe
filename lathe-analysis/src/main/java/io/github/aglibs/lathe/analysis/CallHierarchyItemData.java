package io.github.aglibs.lathe.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import javax.lang.model.element.ElementKind;

// `overridden` are the methods this one overrides, each as its own class declares it, so a call
// through one is found where this method's class is not on the classpath (an upstream module);
// `receiverTypes` are this method's class and its supertypes, the receivers such a call reaches it
// on. Both are empty for a method that overrides nothing.
public record CallHierarchyItemData(
    String ownerBinaryName,
    String methodName,
    String erasedDescriptor,
    ElementKind kind,
    String routingUri,
    ReferenceTarget.SearchScope scope,
    boolean staticMember,
    List<Overridden> overridden,
    List<String> receiverTypes) {

  public CallHierarchyItemData {
    ValidCheck.check()
        .notBlank(ownerBinaryName, "ownerBinaryName")
        .notBlank(methodName, "methodName")
        .notBlank(erasedDescriptor, "erasedDescriptor")
        .notNull(kind, "kind")
        .notBlank(routingUri, "routingUri")
        .notNull(scope, "scope")
        .notNull(overridden, "overridden")
        .notNull(receiverTypes, "receiverTypes")
        .validate();
    overridden = List.copyOf(overridden);
    receiverTypes = List.copyOf(receiverTypes);
  }

  public record Overridden(String ownerBinaryName, String erasedDescriptor) {

    public Overridden {
      ValidCheck.check()
          .notBlank(ownerBinaryName, "ownerBinaryName")
          .notBlank(erasedDescriptor, "erasedDescriptor")
          .validate();
    }
  }

  public ReferenceTarget target() {
    return new ReferenceTarget(
        kind,
        ownerBinaryName,
        methodName,
        erasedDescriptor,
        scope,
        List.of(),
        false,
        staticMember,
        -1);
  }

  // The classes declaring a method this one overrides, each once.
  public List<String> overriddenDeclarers() {
    return overridden.stream().map(Overridden::ownerBinaryName).distinct().toList();
  }
}
