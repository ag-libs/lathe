package io.github.aglibs.lathe.server.analysis;

import java.util.Set;

public record SemanticToken(
    int line, int character, int length, String type, Set<String> modifiers) {}
