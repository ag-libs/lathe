package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import org.eclipse.lsp4j.TypeHierarchyItem;

/**
 * The full both-directions hierarchy of a type: transitive supertypes, the anchor, and subtypes.
 */
public record TypeHierarchyExplorerResult(
    List<TypeHierarchyItem> supertypes,
    TypeHierarchyItem self, // null when the cursor resolved to no type
    List<TypeHierarchyItem> subtypes,
    boolean truncated) { // set when the subtype set hit the node cap

  public TypeHierarchyExplorerResult {
    // self is intentionally nullable (no type resolved at the cursor); the lists never are.
    ValidCheck.check().notNull(supertypes, "supertypes").notNull(subtypes, "subtypes").validate();
    supertypes = List.copyOf(supertypes);
    subtypes = List.copyOf(subtypes);
  }

  public static TypeHierarchyExplorerResult empty() {
    return new TypeHierarchyExplorerResult(List.of(), null, List.of(), false);
  }
}
