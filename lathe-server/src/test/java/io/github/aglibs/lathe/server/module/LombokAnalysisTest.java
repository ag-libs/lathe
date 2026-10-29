package io.github.aglibs.lathe.server.module;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.LombokFixtures;
import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.LearnedStaticImports;
import io.github.aglibs.lathe.server.analysis.ReferenceTarget;
import io.github.aglibs.lathe.server.analysis.SemanticToken;
import io.github.aglibs.lathe.server.analysis.SourceAnalysisSession;
import io.github.aglibs.lathe.server.analysis.SourceFeatureRequest;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.io.TempDir;

// A module whose build has Lombok: its analysis sees what Lombok generates, and the features that
// map source positions to trees answer for the code as written, not for the generated members that
// take its spans (a getter the field's name, the @Data and @Log annotations).
@EnabledForJreRange(maxVersion = LombokFixtures.MAX_JDK)
class LombokAnalysisTest {

  private static final String SOURCE =
      """
      package com.example;

      import lombok.Builder;
      import lombok.Data;
      import lombok.extern.java.Log;

      @Data
      @Builder
      @Log
      class Person {
        private String name;
        private int age;
        private boolean active;

        String greeting() {
          return "Hello, " + name + getName();
        }

        void use() {
          setName("x");
          isActive();
          builder();
          toString();
        }
      }
      """;

  @TempDir private Path td;

  @Test
  void compile_lombokClass_noFalseErrorsAndNoFalseUnusedHints() {
    try (var session = session()) {
      final List<Diagnostic> diagnostics = session.compile(uri(), SOURCE, 1, CompileMode.OPEN);

      assertThat(diagnostics)
          .as("errors, and an unused hint for age, which only its generated accessors use")
          .isEmpty();
    }
  }

  @Test
  void hoverAndReferences_onAFieldsName_answerForTheFieldNotItsGeneratedGetter() {
    try (var session = session()) {
      session.compile(uri(), SOURCE, 1, CompileMode.OPEN);
      final var request = requestAt(position("private String name", "name"));

      assertThat(session.hover(request).getContents().getRight().getValue())
          .contains("String name");
      assertThat(
              session
                  .searchReferences(uri(), SOURCE, 1, session.resolveTarget(request), true)
                  .stream()
                  .map(match -> match.range().getStart()))
          .containsExactlyInAnyOrder(
              position("private String name", "name"), position("+ name +", "name"));
    }
  }

  @Test
  void semanticTokens_lombokClass_tokensOnlyForTheCodeAsWritten() {
    try (var session = session()) {
      session.compile(uri(), SOURCE, 1, CompileMode.OPEN);
      final Position field = position("private String name", "name");

      final List<SemanticToken> tokens = session.semanticTokens(uri(), SOURCE, 1);

      assertThat(tokens)
          .as("one token on the field's name: the field's, not its generated getter's")
          .filteredOn(
              token -> token.line() == field.getLine() && token.character() == field.getCharacter())
          .singleElement()
          .satisfies(token -> assertThat(token.type()).isEqualTo("property"));
      assertThat(tokens)
          .as("no generated member's token on the class's annotation lines")
          .filteredOn(
              token ->
                  token.line() >= position("@Data", "@").getLine()
                      && token.line() <= position("@Log", "@").getLine())
          .noneMatch(token -> token.type().equals("method") || token.type().equals("property"));
    }
  }

  // A generated member has no declaration of its own: definition lands on the code it comes from,
  // the field an accessor reads or writes, else the class.
  @Test
  void definition_onGeneratedMembers_landsOnTheirFieldOrClass() {
    try (var session = session()) {
      session.compile(uri(), SOURCE, 1, CompileMode.OPEN);

      assertThat(definitionAt(session, "+ getName()", "getName"))
          .isEqualTo(position("private String name", "name"));
      assertThat(definitionAt(session, "setName(", "setName"))
          .isEqualTo(position("private String name", "name"));
      assertThat(definitionAt(session, "isActive()", "isActive"))
          .isEqualTo(position("private boolean active", "active"));
      assertThat(definitionAt(session, "builder()", "builder"))
          .isEqualTo(position("class Person", "Person"));
      assertThat(definitionAt(session, "toString()", "toString"))
          .isEqualTo(position("class Person", "Person"));
    }
  }

  // A field's generated accessors are searched with it, as their calls use the field.
  @Test
  void generatedAccessorTargets_onAField_areItsGetterAndSetter_onAMethodNone() {
    try (var session = session()) {
      session.compile(uri(), SOURCE, 1, CompileMode.OPEN);

      assertThat(
              session.generatedAccessorTargets(requestAt(position("private String name", "name"))))
          .extracting(ReferenceTarget::simpleName)
          .containsExactlyInAnyOrder("getName", "setName");
      assertThat(
              session.generatedAccessorTargets(requestAt(position("String greeting", "greeting"))))
          .isEmpty();
    }
  }

  // A field and what Lombok generates from it go together: renaming either alone breaks them apart.
  @Test
  void renameConflict_lombokFieldOrGeneratedMember_refused_ordinaryMethodAllowed() {
    try (var session = session()) {
      session.compile(uri(), SOURCE, 1, CompileMode.OPEN);

      assertThat(
              session.renameConflict(requestAt(position("private String name", "name")), "label"))
          .hasValueSatisfying(
              reason ->
                  assertThat(reason).contains("Lombok generates getName(), setName() from it"));
      assertThat(session.renameConflict(requestAt(position("+ getName()", "getName")), "label"))
          .hasValueSatisfying(
              reason -> assertThat(reason).contains("Lombok generates it from `name`"));
      assertThat(
              session.renameConflict(requestAt(position("String greeting", "greeting")), "hello"))
          .isEmpty();
    }
  }

  // Completion offers what Lombok generates, also while the line being typed does not parse: the
  // receiver resolves against the module's last clean analysis.
  @Test
  void complete_whileTypingOnALombokInstance_offersItsGeneratedAccessors() {
    final String opened =
        SOURCE.replace("void use() {", "void use(final Person p) {\n    p.age();");
    final String typing = opened.replace("p.age();", "p.get");
    try (var session = session()) {
      session.compile(uri(), opened, 1, CompileMode.OPEN);

      final var outcome =
          session.complete(
              uri(),
              typing,
              2,
              SourceLocator.offsetToPosition(typing, typing.indexOf("p.get") + "p.get".length()),
              null,
              WorkspaceTypeIndex.build(List.of(), List.of()),
              List.of(),
              LearnedStaticImports.empty());

      assertThat(outcome.items())
          .extracting(CompletionItem::getLabel)
          .anyMatch(label -> label.startsWith("getName"))
          .anyMatch(label -> label.startsWith("getAge"));
    }
  }

  private Position definitionAt(
      final SourceAnalysisSession session, final String context, final String token) {
    return session
        .definition(requestAt(position(context, token)))
        .orElseThrow()
        .getRange()
        .getStart();
  }

  private SourceAnalysisSession session() {
    return new SourceAnalysisSession(
        new ModuleSourceCompiler(
            LombokFixtures.moduleConfig(td, sourceRoot(), null), new CompilationAdmission(1)));
  }

  private Path sourceRoot() {
    return td.resolve("src/main/java");
  }

  private String uri() {
    return sourceRoot().resolve("com/example/Person.java").toUri().toString();
  }

  private SourceFeatureRequest requestAt(final Position position) {
    return new SourceFeatureRequest(
        uri(), SOURCE, 1, position, List.of(sourceRoot()), WorkspaceManifest.empty());
  }

  private static Position position(final String context, final String token) {
    return SourceLocator.offsetToPosition(SOURCE, SOURCE.indexOf(token, SOURCE.indexOf(context)));
  }
}
