package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.Tree;
import com.sun.source.util.TreePathScanner;

// A scanner over the source as written. It skips what Lombok generates: those members take the
// spans of the code they come from, a field's name or an annotation, and would otherwise answer for
// that code (highlighting, outline, folding, references, unused-code hints).
abstract class WrittenSourceScanner extends TreePathScanner<Void, Void> {

  @Override
  public Void scan(final Tree tree, final Void unused) {
    return SourceLocator.isGenerated(tree) ? null : super.scan(tree, unused);
  }
}
