package io.github.aglibs.lathe.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import io.github.aglibs.validcheck.ValidCheck;
import javax.lang.model.element.Element;

// A position in an attributed file: its analysis, the tree path at the offset, and the offset.
public record Cursor(AttributedFileAnalysis analysis, TreePath path, long offset) {

  public Cursor {
    ValidCheck.check().notNull(analysis, "analysis").validate();
  }

  // The element at the cursor, or the Javadoc reference tag ({@link} / @see / @throws) under it.
  // A member's Javadoc lies outside the member's tree, so its path ends at the enclosing class (or
  // the compilation unit), which elementAt would resolve to: consult the Javadoc there first.
  // Shared by hover, definition, references, and rename.
  public Element elementOrJavadocReference() {
    final Tree leaf = path != null ? path.getLeaf() : null;
    if (leaf == null || leaf instanceof ClassTree || leaf instanceof CompilationUnitTree) {
      final Element reference = JavadocReferenceResolver.referenceAt(analysis, offset);
      if (reference != null) {
        return reference;
      }
    }

    return CursorElements.elementAt(analysis.trees(), path);
  }
}
