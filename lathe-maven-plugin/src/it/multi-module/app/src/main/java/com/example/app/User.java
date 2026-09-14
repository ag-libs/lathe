package com.example.app;

import io.github.aglibs.recordcompanion.builder.Builder;

/**
 * Domain record. The {@link Builder} annotation is read at compile time by the record-companion
 * annotation processor, which generates a fluent {@code UserBuilder} companion — one setter per
 * component, regenerated on every build.
 */
@Builder
public record User(String name, int age) {}
