package com.example.verify;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.itsupport.LspSession;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

// A Lombok project as its users build it: Lombok on annotationProcessorPaths, and the members it
// generates used inside the annotated class (Person) and from another (User). Lathe compiles it on a
// save without failing, but does not see the generated members yet. The checks of that pin today's
// answers and say the right ones, so they fail once it is fixed and must then be turned around.
@Timeout(120)
class LombokTest {

  private static final Path MODEL =
      LspSession.ROOT.resolve("model/src/main/java/com/example/model");
  private static final Path PERSON = MODEL.resolve("Person.java");
  private static final Path USER = MODEL.resolve("User.java");
  // The class Lathe keeps for Person, which its save writes.
  private static final Path PERSON_CLASS =
      LspSession.ROOT.resolve(".lathe/model/classes/com/example/model/Person.class");

  private static LspSession session;

  @BeforeAll
  static void startServer() throws Exception {
    session = LspSession.start(new ClientCapabilities());
  }

  @AfterAll
  static void stopServer() {
    session.stop();
  }

  // A save compiles the module with Lombok, so the class Lathe keeps gains what Lombok generates
  // for a new field. The file's own diagnostics come from a check that runs no annotation
  // processor, before the save and after it, so they report the generated getter as missing.
  // Right: no diagnostic.
  @Test
  void save_lombokClass_compilesWithLombok_butReportsTheGeneratedGetterMissing() throws Exception {
    final String uri = PERSON.toUri().toString();
    final String original = Files.readString(PERSON);
    final String withEmail =
        original.replace("private String name;", "private String name;\n  private String email;");
    try {
      session.client.diagnostics.clear();
      session.openDoc(uri, original);
      assertThat(nextDiagnostics(uri)).as("on open").anyMatch(LombokTest::isMissingGetName);

      session.client.diagnostics.clear();
      session.saveDoc(uri, withEmail);
      awaitInPersonClass("getEmail");
      assertThat(nextDiagnostics(uri)).as("after the save").anyMatch(LombokTest::isMissingGetName);
    } finally {
      session.saveDoc(uri, original);
    }
  }

  // Right: the field name, which the getter is generated from. Today: nothing, since no source
  // declares getName.
  @Test
  void definition_lombokGetterCall_findsNoDeclaration() throws Exception {
    final String uri = USER.toUri().toString();
    final String content = Files.readString(USER);
    session.openDoc(uri, content);

    final var params =
        new DefinitionParams(
            new TextDocumentIdentifier(uri),
            LspSession.findToken(content, "person.getName", "getName"));
    final Either<List<? extends Location>, List<? extends LocationLink>> definition =
        session.server.getTextDocumentService().definition(params).get(30, SECONDS);

    assertThat(definition == null || definition.isLeft() && definition.getLeft().isEmpty())
        .as("no definition for %s", definition)
        .isTrue();
  }

  // Right: the field's declaration and its getter's call in User. Today: the declaration alone.
  @Test
  void references_lombokField_missTheGetterCall() throws Exception {
    final String uri = PERSON.toUri().toString();
    final String content = Files.readString(PERSON);
    session.openDoc(uri, content);

    final var params =
        new ReferenceParams(
            new TextDocumentIdentifier(uri),
            LspSession.findToken(content, "private String name", "name"),
            new ReferenceContext(true));
    final List<? extends Location> references =
        session.server.getTextDocumentService().references(params).get(30, SECONDS);

    assertThat(references)
        .singleElement()
        .satisfies(location -> assertThat(location.getUri()).endsWith("Person.java"));
  }

  private static boolean isMissingGetName(final Diagnostic diagnostic) {
    final var message = diagnostic.getMessage();
    final String text = message.isLeft() ? message.getLeft() : message.getRight().getValue();
    return text.contains("cannot find symbol") && text.contains("getName()");
  }

  private static List<Diagnostic> nextDiagnostics(final String uri) throws InterruptedException {
    while (true) {
      final PublishDiagnosticsParams published = session.client.diagnostics.poll(60, SECONDS);
      assertThat(published).as("diagnostics for %s within 60 s", uri).isNotNull();
      if (published.getUri().endsWith(uri.substring(uri.lastIndexOf('/')))) {
        return published.getDiagnostics();
      }
    }
  }

  // The class file's constant pool names each method, so a generated getter shows as its name.
  private static void awaitInPersonClass(final String member) throws Exception {
    for (int i = 0; i < 120; i++) {
      if (new String(Files.readAllBytes(PERSON_CLASS), StandardCharsets.ISO_8859_1).contains(member)) {
        return;
      }

      Thread.sleep(500);
    }

    throw new AssertionError("%s never appeared in %s".formatted(member, PERSON_CLASS));
  }
}
