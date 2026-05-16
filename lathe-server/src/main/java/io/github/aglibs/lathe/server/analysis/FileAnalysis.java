package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.server.tokens.SemanticToken;
import java.util.List;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

public record FileAnalysis(
    Trees trees,
    Elements elements,
    Types types,
    CompilationUnitTree tree,
    List<SemanticToken> semanticTokens) {}
