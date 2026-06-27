package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.Tree;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import org.eclipse.lsp4j.SymbolKind;

final class SymbolKinds {

  private SymbolKinds() {}

  static SymbolKind fromTypeIndex(final TypeKind kind) {
    return switch (kind) {
      case INTERFACE, ANNOTATION -> SymbolKind.Interface;
      case ENUM -> SymbolKind.Enum;
      case RECORD -> SymbolKind.Struct;
      default -> SymbolKind.Class;
    };
  }

  static SymbolKind fromTree(final Tree.Kind kind) {
    return switch (kind) {
      case ANNOTATION_TYPE, INTERFACE -> SymbolKind.Interface;
      case ENUM -> SymbolKind.Enum;
      case RECORD -> SymbolKind.Struct;
      default -> SymbolKind.Class;
    };
  }
}
