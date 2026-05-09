package io.github.aglibs.lathe.server;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.Trees;
import java.util.List;

record CompilationTaskContext(
    Trees trees, CompilationUnitTree tree, List<SemanticToken> semanticTokens) {}
