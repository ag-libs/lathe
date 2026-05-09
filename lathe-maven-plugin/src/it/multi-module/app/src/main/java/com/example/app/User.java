package com.example.app;

import io.github.aglibs.recordcompanion.builder.Builder;

@Builder
public record User(String name, int age) {}
