package io.github.aglibs.lathe.server;

import java.util.Set;

record SemanticToken(int line, int character, int length, String type, Set<String> modifiers) {}
