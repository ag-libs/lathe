package io.github.aglibs.lathe.format;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs the test once per {@code .java} file under {@code -Dlathe.format.corpus=<dir>}; skipped when
 * the property is not set, since the corpora live outside the repo.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ParameterizedTest(name = "{0}")
@MethodSource("io.github.aglibs.lathe.format.CorpusProvider#corpusFiles")
@EnabledIfSystemProperty(named = CorpusProvider.CORPUS_PROPERTY, matches = ".+")
@interface CorpusFiles {}
