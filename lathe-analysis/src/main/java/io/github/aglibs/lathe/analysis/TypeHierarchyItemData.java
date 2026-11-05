package io.github.aglibs.lathe.analysis;

import io.github.aglibs.validcheck.ValidCheck;

public record TypeHierarchyItemData(String binaryName, String routingUri) {

  public TypeHierarchyItemData {
    ValidCheck.check()
        .notBlank(binaryName, "binaryName")
        .notBlank(routingUri, "routingUri")
        .validate();
  }
}
