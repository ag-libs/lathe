package io.github.aglibs.lathe.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import javax.lang.model.element.ElementKind;

// An item names a method or constructor, or the field or initializer a call outside any method
// runs in (EG-122): those have no descriptor, are never called, and list only outgoing calls.
// `declarationOffset` is an initializer block's start, as a class may have several of a kind; -1
// for anything else. `overridden` are the methods this one overrides, each as its own class
// declares it, so a call
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
    List<String> receiverTypes,
    long declarationOffset) {

  public CallHierarchyItemData {
    ValidCheck.check()
        .notBlank(ownerBinaryName, "ownerBinaryName")
        .notBlank(methodName, "methodName")
        .notNull(erasedDescriptor, "erasedDescriptor")
        .notNull(kind, "kind")
        .notBlank(routingUri, "routingUri")
        .notNull(scope, "scope")
        .notNull(overridden, "overridden")
        .notNull(receiverTypes, "receiverTypes")
        .validate();
    ValidCheck.check()
        .assertTrue(!isCallable(kind) || !erasedDescriptor.isBlank(), "erasedDescriptor")
        .assertTrue(declarationOffset >= -1, "declarationOffset")
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

  public boolean callable() {
    return isCallable(kind);
  }

  private static boolean isCallable(final ElementKind kind) {
    return kind == ElementKind.METHOD || kind == ElementKind.CONSTRUCTOR;
  }

  public ReferenceTarget target() {
    return ReferenceTarget.ofExecutable(
        kind, ownerBinaryName, methodName, erasedDescriptor, scope, staticMember);
  }

  // The classes declaring a method this one overrides, each once.
  public List<String> overriddenDeclarers() {
    return overridden.stream().map(Overridden::ownerBinaryName).distinct().toList();
  }
}
