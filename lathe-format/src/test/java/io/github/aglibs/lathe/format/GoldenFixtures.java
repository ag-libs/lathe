package io.github.aglibs.lathe.format;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Runs the test once per version-eligible golden fixture: {@code (name, input, expected)}. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ParameterizedTest(name = "{0}")
@MethodSource("io.github.aglibs.lathe.format.CorpusProvider#goldenCases")
@interface GoldenFixtures {}
