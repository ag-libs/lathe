package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.server.tokens.SemanticToken;
import java.util.List;

public record FileAnalysis(
    Trees trees, CompilationUnitTree tree, List<SemanticToken> semanticTokens) {}
