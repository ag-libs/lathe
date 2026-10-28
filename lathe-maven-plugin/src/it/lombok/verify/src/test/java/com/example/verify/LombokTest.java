package com.example.verify;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.itsupport.LspSession;
import com.example.itsupport.McpSession;
import com.example.itsupport.Os;
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
import org.eclipse.lsp4j.RenameParams;
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
  private static McpSession mcp;

  @BeforeAll
  static void startServers() throws Exception {
    session = LspSession.start(new ClientCapabilities());
    mcp = McpSession.start();
  }

  @AfterAll
  static void stopServers() {
    session.stop();
    mcp.stop();
  }

  // An agent gets the same answers through MCP: no generated member reported missing, definition on
  // a generated getter lands on its field, and the field's references include the getter's call.
  @Test
  void mcp_lombokProject_seesGeneratedMembers() throws Exception {
    assertThat(mcp.callTool("get_diagnostics", "{\"file\":\"%s\"}".formatted(Os.json(PERSON))))
        .contains("No errors")
        .doesNotContain("getName()");

    final int[] call = McpSession.tokenPosition(USER, "getName");
    assertThat(
            mcp.callTool(
                "get_definition",
                "{\"file\":\"%s\",\"line\":%d,\"column\":%d}"
                    .formatted(Os.json(USER), call[0], call[1])))
        .contains("Person.java:%d".formatted(McpSession.tokenPosition(PERSON, "private String name")[0]));

    final int[] field = McpSession.tokenPosition(PERSON, "name;");
    assertThat(
            mcp.callTool(
                "find_references",
                "{\"file\":\"%s\",\"line\":%d,\"column\":%d}"
                    .formatted(Os.json(PERSON), field[0], field[1])))
        .contains("User.java")
        .contains("Person.java");

    final String person = Files.readString(PERSON);
    assertThat(
            mcp.callTool(
                "rename_symbol",
                "{\"file\":\"%s\",\"line\":%d,\"column\":%d,\"newName\":\"label\"}"
                    .formatted(Os.json(PERSON), field[0], field[1])))
        .contains("\"isError\":true")
        .contains("Lombok generates getName(), setName() from it");
    assertThat(Files.readString(PERSON)).isEqualTo(person);
  }

  // Renaming a field Lombok generates accessors from would leave their calls behind: the rename is
  // refused with the reason, and nothing is edited.
  @Test
  void rename_lombokField_refusedWithTheReason() throws Exception {
    final String uri = PERSON.toUri().toString();
    final String content = Files.readString(PERSON);
    session.openDoc(uri, content);

    final var params =
        new RenameParams(
            new TextDocumentIdentifier(uri),
            LspSession.findToken(content, "private String name", "name"),
            "label");

    assertThatThrownBy(() -> session.server.getTextDocumentService().rename(params).get(30, SECONDS))
        .hasMessageContaining("Lombok generates getName(), setName() from it");
    assertThat(Files.readString(PERSON)).isEqualTo(content);
  }

  // A save compiles the module with Lombok, so the class Lathe keeps gains what Lombok generates
  // for a new field; the file's own analysis runs Lombok too, so on open and after the save it
  // reports none of the generated members missing.
  @Test
  void save_lombokClass_compilesWithLombok_andReportsNoGeneratedMemberMissing() throws Exception {
    final String uri = PERSON.toUri().toString();
    final String original = Files.readString(PERSON);
    final String withEmail =
        original.replace("private String name;", "private String name;\n  private String email;");
    try {
      session.client.diagnostics.clear();
      session.openDoc(uri, original);
      assertThat(nextDiagnostics(uri)).as("on open").noneMatch(LombokTest::isMissingGetName);

      session.client.diagnostics.clear();
      session.saveDoc(uri, withEmail);
      awaitInPersonClass("getEmail");
      assertThat(nextDiagnostics(uri)).as("after the save").noneMatch(LombokTest::isMissingGetName);
    } finally {
      session.saveDoc(uri, original);
    }
  }

  // A generated getter has no declaration of its own: definition lands on the field it reads.
  @Test
  void definition_lombokGetterCall_landsOnTheField() throws Exception {
    final String uri = USER.toUri().toString();
    final String content = Files.readString(USER);
    session.openDoc(uri, content);

    final var params =
        new DefinitionParams(
            new TextDocumentIdentifier(uri),
            LspSession.findToken(content, "person.getName", "getName"));
    final Either<List<? extends Location>, List<? extends LocationLink>> definition =
        session.server.getTextDocumentService().definition(params).get(30, SECONDS);

    assertThat(definition.getLeft())
        .singleElement()
        .satisfies(
            location -> {
              assertThat(location.getUri()).endsWith("Person.java");
              assertThat(location.getRange().getStart())
                  .isEqualTo(
                      LspSession.findToken(
                          Files.readString(PERSON), "private String name", "name"));
            });
  }

  // A field is used through its generated getter too: references find the getter's calls, in Person
  // itself and in User.
  @Test
  void references_lombokField_includeTheGetterCall() throws Exception {
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

    final String user = Files.readString(USER);
    assertThat(references)
        .extracting(
            location ->
                location.getUri().substring(location.getUri().lastIndexOf('/') + 1)
                    + " "
                    + location.getRange().getStart())
        .containsExactlyInAnyOrder(
            "Person.java " + LspSession.findToken(content, "private String name", "name"),
            "Person.java " + LspSession.findToken(content, "+ getName()", "getName"),
            "User.java " + LspSession.findToken(user, "person.getName", "getName"));
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
