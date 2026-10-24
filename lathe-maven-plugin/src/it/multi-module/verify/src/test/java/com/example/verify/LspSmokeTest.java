package com.example.verify;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.RenameFile;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.ResourceOperationKind;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceEditCapabilities;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.TypeHierarchyPrepareParams;
import org.eclipse.lsp4j.TypeHierarchySubtypesParams;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class LspSmokeTest {

  private static final Path ROOT = LspSession.ROOT;
  private static final Path MANIFEST = ROOT.resolve(".lathe/workspace.json");
  private static final Path SHAPES = ROOT.resolve("shapes");
  private static final String ID_SHAPES = "com.example.shapes.IdShapesTest";
  private static final int TEST_METHOD = 1;
  private static final int STDOUT = 0;
  private static final int STDERR = 1;
  private static final int COMMAND = 2;

  private static LspSession session;
  private static LanguageServer server;
  private static LspSession.CapturingClient client;
  private static InitializeResult initResult;
  private static FileTime originalPomMtime;
  private static String originalManifest;

  @BeforeAll
  static void startServer() throws Exception {
    originalPomMtime = Files.getLastModifiedTime(ROOT.resolve("pom.xml"));
    originalManifest = Files.readString(MANIFEST);
    session = LspSession.start(renameCapableClient());
    server = session.server;
    client = session.client;
    initResult = session.initResult;
  }

  @AfterAll
  static void stopServer() {
    session.stop();
  }

  @AfterEach
  void resetState() throws Exception {
    Files.setLastModifiedTime(ROOT.resolve("pom.xml"), originalPomMtime);
    if (!Files.readString(MANIFEST).equals(originalManifest)) {
      // Restoring the manifest content is itself a structural change → a reload notification. Drain
      // it here so it does not leak into the next test's message assertions.
      Files.writeString(MANIFEST, originalManifest);
      client.messages.poll(10, SECONDS);
    }

    client.messages.clear();
    client.syncPrompts.clear();
  }

  @Test
  void initialize_returnsExpectedCapabilities() {
    final var caps = initResult.getCapabilities();
    assertThat(caps.getCompletionProvider()).isNotNull();
    assertThat(caps.getHoverProvider()).isNotNull();
    assertThat(caps.getDefinitionProvider()).isNotNull();
    assertThat(caps.getImplementationProvider()).isNotNull();
    assertThat(caps.getTypeHierarchyProvider()).isNotNull();
    assertThat(caps.getReferencesProvider()).isNotNull();
    // Formatting is per-workspace: this fixture's spotless googleJavaFormat config makes lathe:sync
    // write .lathe/style.json with the google engine, so the server advertises formatting here.
    assertThat(caps.getDocumentFormattingProvider()).isNotNull();
    // The pinned google-java-format runs in-process, so it formats ranges too.
    assertThat(caps.getDocumentRangeFormattingProvider()).isNotNull();
    assertThat(caps.getSemanticTokensProvider()).isNotNull();
  }

  // End to end through what sync wrote: style.json's resolved google-java-format jars, loaded in
  // an isolated classloader, with jvm.args granting the javac internals it needs.
  @Test
  void formatting_pinnedGoogleJavaFormat_formatsDocumentAndRange() throws Exception {
    final Path stringUtilsJava =
        ROOT.resolve("core/src/main/java/com/example/core/StringUtils.java");
    final var uri = stringUtilsJava.toUri().toString();
    final var mangled =
        Files.readString(stringUtilsJava)
            .replace("public static String upper", "public   static String   upper");
    session.openDoc(uri, mangled);
    final var document = new TextDocumentIdentifier(uri);
    final var options = new FormattingOptions(2, true);
    final int line = findToken(mangled, "public   static String   upper", "upper").getLine();

    try {
      final List<? extends TextEdit> whole =
          server
              .getTextDocumentService()
              .formatting(new DocumentFormattingParams(document, options))
              .get(30, SECONDS);
      final List<? extends TextEdit> ranged =
          server
              .getTextDocumentService()
              .rangeFormatting(
                  new DocumentRangeFormattingParams(
                      document,
                      options,
                      new Range(new Position(line, 0), new Position(line + 1, 0))))
              .get(30, SECONDS);

      assertThat(whole).isNotEmpty();
      assertThat(ranged)
          .singleElement()
          .satisfies(edit -> assertThat(edit.getRange().getStart().getLine()).isEqualTo(line));
    } finally {
      server.getTextDocumentService().didClose(new DidCloseTextDocumentParams(document));
    }
  }

  @Test
  void workspaceReload_manifestContentChanged_notifiesUser() throws Exception {
    // A manifest content change is structural → full reload + notification (a mtime-only touch is
    // the silent refresh). Appending a newline keeps the JSON valid but changes the compared bytes.
    Files.writeString(MANIFEST, originalManifest + "\n");

    final var msg = client.messages.poll(10, SECONDS);
    assertThat(msg).isNotNull();
    assertThat(msg.getMessage()).isEqualTo("Lathe: workspace reloaded.");
  }

  @Test
  void workspaceSymbol_reactorType_resolvesAcrossModules() throws Exception {
    // Reactor types from different modules must resolve via workspace/symbol.
    assertThat(symbolNames("Greeter")).contains("Greeter");
    assertThat(symbolNames("MongoDbClient")).contains("MongoDbClient");
  }

  @Test
  void references_fromReactorSource_findsUsageAcrossModules() throws Exception {
    final Path stringUtilsJava =
        ROOT.resolve("core/src/main/java/com/example/core/StringUtils.java");
    final var stringUtilsUri = stringUtilsJava.toUri().toString();
    final var stringUtilsContent = Files.readString(stringUtilsJava);
    session.openDoc(stringUtilsUri, stringUtilsContent);

    final var params = new ReferenceParams();
    params.setTextDocument(new TextDocumentIdentifier(stringUtilsUri));
    params.setPosition(findToken(stringUtilsContent, "public static String upper", "upper"));
    params.setContext(new ReferenceContext(false));

    final List<? extends Location> refs =
        server.getTextDocumentService().references(params).get(30, SECONDS);

    assertThat(refs).anyMatch(loc -> loc.getUri().contains("Main.java"));
  }

  @Test
  void references_fromCachedJdkSource_findsReactorUsages() throws Exception {
    // FR-001: cursorConfig is empty for external sources → configs = List.of() → no search
    final Path stringJava =
        Files.find(
                Path.of(System.getProperty("lathe.cache")).resolve("jdks"),
                6,
                (p, a) ->
                    p.getParent().endsWith(Path.of("java.base", "java", "lang"))
                        && p.getFileName().toString().equals("String.java"))
            .findFirst()
            .orElseThrow();

    final String stringUri = stringJava.toUri().toString();
    final String stringContent = Files.readString(stringJava);
    session.openDoc(stringUri, stringContent);

    final var params = new ReferenceParams();
    params.setTextDocument(new TextDocumentIdentifier(stringUri));
    params.setPosition(findToken(stringContent, "public final class String", "String"));
    params.setContext(new ReferenceContext(false));

    final List<? extends Location> refs =
        server.getTextDocumentService().references(params).get(30, SECONDS);

    assertThat(refs).anyMatch(loc -> loc.getUri().contains("StringUtils.java"));
  }

  @Test
  void references_fromBaseMethod_findsOverrideDeclarationAndCallSite() throws Exception {
    final Path dbClientJava = ROOT.resolve("core/src/main/java/com/example/core/DbClient.java");
    final String dbClientUri = dbClientJava.toUri().toString();
    final String dbClientContent = Files.readString(dbClientJava);
    session.openDoc(dbClientUri, dbClientContent);

    final var params = new ReferenceParams();
    params.setTextDocument(new TextDocumentIdentifier(dbClientUri));
    params.setPosition(findToken(dbClientContent, "String dbType()", "dbType"));
    params.setContext(new ReferenceContext(true));

    final List<? extends Location> refs =
        server.getTextDocumentService().references(params).get(30, SECONDS);

    assertThat(refs)
        .anyMatch(loc -> loc.getUri().contains("MongoDbClient.java"))
        .anyMatch(loc -> loc.getUri().contains("DbClientHealthCheck.java"));
  }

  @Test
  void references_fromOverrideMethod_findsSupertypeTypedCallSite() throws Exception {
    final Path mongoClientJava =
        ROOT.resolve("app/src/main/java/com/example/app/MongoDbClient.java");
    final String mongoClientUri = mongoClientJava.toUri().toString();
    final String mongoClientContent = Files.readString(mongoClientJava);
    session.openDoc(mongoClientUri, mongoClientContent);

    final var params = new ReferenceParams();
    params.setTextDocument(new TextDocumentIdentifier(mongoClientUri));
    params.setPosition(findToken(mongoClientContent, "public String dbType()", "dbType"));
    params.setContext(new ReferenceContext(true));

    final List<? extends Location> refs =
        server.getTextDocumentService().references(params).get(30, SECONDS);

    assertThat(refs).anyMatch(loc -> loc.getUri().contains("DbClientHealthCheck.java"));
  }

  @Test
  void implementation_typeCursor_findsImplementationsAcrossModules() throws Exception {
    final Path greeterJava =
        ROOT.resolve("core/src/main/java/com/example/core/Greeter.java");
    final String greeterUri = greeterJava.toUri().toString();
    final String greeterContent = Files.readString(greeterJava);
    session.openDoc(greeterUri, greeterContent);

    final var params = new ImplementationParams();
    params.setTextDocument(new TextDocumentIdentifier(greeterUri));
    params.setPosition(findToken(greeterContent, "public interface Greeter", "Greeter"));

    final List<? extends Location> impls =
        server.getTextDocumentService().implementation(params).get(30, SECONDS).getLeft();

    assertThat(impls)
        .anyMatch(loc -> loc.getUri().contains("FormalGreeter.java"))
        .anyMatch(loc -> loc.getUri().contains("CasualGreeter.java"));
  }

  @Test
  void implementation_samMethodCursor_findsLambdaAndMethodReference() throws Exception {
    // Cursor on the functional-interface SAM (greet) returns the named *Greeter classes, the lambda
    // and method-reference implementations that spell Greeter (app/Main.java), and — via the
    // compiled-bytecode lambda index — an argument-position lambda whose source never names Greeter
    // (app/Dispatcher.java).
    final Path greeterJava = ROOT.resolve("core/src/main/java/com/example/core/Greeter.java");
    final String greeterUri = greeterJava.toUri().toString();
    final String greeterContent = Files.readString(greeterJava);
    session.openDoc(greeterUri, greeterContent);

    final var params = new ImplementationParams();
    params.setTextDocument(new TextDocumentIdentifier(greeterUri));
    params.setPosition(findToken(greeterContent, "String greet(String", "greet"));

    final List<? extends Location> impls =
        server.getTextDocumentService().implementation(params).get(30, SECONDS).getLeft();

    assertThat(impls.stream().map(Location::getUri).toList())
        .anyMatch(uri -> uri.contains("FormalGreeter.java"))
        .anyMatch(uri -> uri.contains("CasualGreeter.java"))
        .anyMatch(uri -> uri.contains("Dispatcher.java"));
    assertThat(impls).filteredOn(loc -> loc.getUri().contains("Main.java")).hasSize(2);
  }

  @Test
  void typeHierarchy_subtypes_returnsImplementorsAcrossModules() throws Exception {
    final Path greeterJava =
        ROOT.resolve("core/src/main/java/com/example/core/Greeter.java");
    final String greeterUri = greeterJava.toUri().toString();
    final String greeterContent = Files.readString(greeterJava);
    session.openDoc(greeterUri, greeterContent);

    final var prepareParams = new TypeHierarchyPrepareParams();
    prepareParams.setTextDocument(new TextDocumentIdentifier(greeterUri));
    prepareParams.setPosition(findToken(greeterContent, "public interface Greeter", "Greeter"));

    final List<TypeHierarchyItem> items =
        server.getTextDocumentService().prepareTypeHierarchy(prepareParams).get(30, SECONDS);

    assertThat(items).hasSize(1);
    assertThat(items.get(0).getName()).isEqualTo("Greeter");

    final var subtypesParams = new TypeHierarchySubtypesParams();
    subtypesParams.setItem(items.get(0));

    final List<TypeHierarchyItem> subtypes =
        server.getTextDocumentService().typeHierarchySubtypes(subtypesParams).get(30, SECONDS);

    assertThat(subtypes)
        .extracting(TypeHierarchyItem::getName)
        .containsExactlyInAnyOrder("FormalGreeter", "CasualGreeter");
  }

  @Test
  void rename_publicType_editsAcrossModulesAndRenamesDeclaringFile() throws Exception {
    final Path greeterJava = ROOT.resolve("core/src/main/java/com/example/core/Greeter.java");
    final String greeterUri = greeterJava.toUri().toString();
    final String greeterContent = Files.readString(greeterJava);
    session.openDoc(greeterUri, greeterContent);

    final var params = new RenameParams();
    params.setTextDocument(new TextDocumentIdentifier(greeterUri));
    params.setPosition(findToken(greeterContent, "interface Greeter", "Greeter"));
    params.setNewName("Salutation");

    final WorkspaceEdit edit =
        server.getTextDocumentService().rename(params).get(30, SECONDS);

    // A public top-level type rename is a resource operation: documentChanges, not the plain map.
    assertThat(edit.getDocumentChanges()).isNotNull();
    final List<String> editedFiles =
        edit.getDocumentChanges().stream()
            .filter(org.eclipse.lsp4j.jsonrpc.messages.Either::isLeft)
            .map(change -> change.getLeft().getTextDocument().getUri())
            .toList();
    assertThat(editedFiles)
        .anyMatch(uri -> uri.contains("core/src/main/java/com/example/core/Greeter.java"))
        .anyMatch(uri -> uri.contains("app/src/main/java/com/example/app/FormalGreeter.java"));

    final List<RenameFile> renames =
        edit.getDocumentChanges().stream()
            .filter(org.eclipse.lsp4j.jsonrpc.messages.Either::isRight)
            .map(change -> (RenameFile) change.getRight())
            .toList();
    assertThat(renames).hasSize(1);
    assertThat(renames.get(0).getOldUri()).endsWith("/core/src/main/java/com/example/core/Greeter.java");
    assertThat(renames.get(0).getNewUri()).endsWith("/core/src/main/java/com/example/core/Salutation.java");
  }

  @Test
  void incomingCalls_publicMethod_findsCrossModuleCaller() throws Exception {
    final Path stringUtilsJava =
        ROOT.resolve("core/src/main/java/com/example/core/StringUtils.java");
    final var stringUtilsUri = stringUtilsJava.toUri().toString();
    final var stringUtilsContent = Files.readString(stringUtilsJava);
    session.openDoc(stringUtilsUri, stringUtilsContent);

    final var prepParams = new CallHierarchyPrepareParams();
    prepParams.setTextDocument(new TextDocumentIdentifier(stringUtilsUri));
    prepParams.setPosition(findToken(stringUtilsContent, "public static String upper", "upper"));

    final List<CallHierarchyItem> items =
        server.getTextDocumentService().prepareCallHierarchy(prepParams).get(30, SECONDS);
    assertThat(items).hasSize(1);
    assertThat(items.get(0).getName()).isEqualTo("upper");

    final List<CallHierarchyIncomingCall> calls =
        server
            .getTextDocumentService()
            .callHierarchyIncomingCalls(new CallHierarchyIncomingCallsParams(items.get(0)))
            .get(30, SECONDS);

    assertThat(calls).anyMatch(call -> call.getFrom().getUri().contains("Main.java"));
  }

  @Test
  void outgoingCalls_calleeInCachedJdkSource_returnsJdkCallee() throws Exception {
    // EG-011: StringUtils.upper's only callee is String.toUpperCase, whose source lives in the
    // extracted JDK cache. Outgoing calls must include it — a reactor-only search root drops it.
    final Path stringUtilsJava =
        ROOT.resolve("core/src/main/java/com/example/core/StringUtils.java");
    final var stringUtilsUri = stringUtilsJava.toUri().toString();
    final var stringUtilsContent = Files.readString(stringUtilsJava);
    session.openDoc(stringUtilsUri, stringUtilsContent);

    final var prepParams = new CallHierarchyPrepareParams();
    prepParams.setTextDocument(new TextDocumentIdentifier(stringUtilsUri));
    prepParams.setPosition(findToken(stringUtilsContent, "public static String upper", "upper"));

    final List<CallHierarchyItem> items =
        server.getTextDocumentService().prepareCallHierarchy(prepParams).get(30, SECONDS);
    assertThat(items).hasSize(1);

    final List<CallHierarchyOutgoingCall> calls =
        server
            .getTextDocumentService()
            .callHierarchyOutgoingCalls(new CallHierarchyOutgoingCallsParams(items.get(0)))
            .get(30, SECONDS);

    assertThat(calls)
        .anyMatch(
            call ->
                call.getTo().getName().equals("toUpperCase")
                    && call.getTo().getUri().contains("/jdks/"));
  }

  @Test
  void pomChange_triggersResyncPrompt() throws Exception {
    // A content edit: the server compares POMs by content, so a mere touch prompts nothing.
    final Path pom = ROOT.resolve("pom.xml");
    final String original = Files.readString(pom);
    Files.writeString(pom, original.replace("</project>", "<!-- lsp smoke --></project>"));
    try {
      final var prompt = client.syncPrompts.poll(10, SECONDS);
      assertThat(prompt).isNotNull();
      assertThat(prompt.getMessage()).contains("Build files changed");
      assertThat(prompt.getActions())
          .extracting(MessageActionItem::getTitle)
          .containsExactlyInAnyOrder("Sync", "Sync + capture tests", "Later");
    } finally {
      Files.writeString(pom, original);
    }
  }

  @Test
  @Timeout(value = 10, unit = SECONDS)
  void completion_afterUpstreamSave_offersNewMethodInDownstreamFileWithoutKeystroke()
      throws Exception {
    // Add a method to core/StringUtils, save it, and assert it becomes visible to completion in
    // app/Main -- a downstream module -- WITHOUT any edit to Main. Cross-module completion resolves
    // the upstream type from the compiled .lathe mirror, which a save refreshes; the downstream open
    // file must then be recompiled so its cached analysis reflects the new member.
    final Path stringUtilsJava =
        ROOT.resolve("core/src/main/java/com/example/core/StringUtils.java");
    final var stringUtilsUri = stringUtilsJava.toUri().toString();
    final String original = Files.readString(stringUtilsJava);
    final String upperMethod =
        """
          public static String upper(final String s) {
            return s.toUpperCase();
          }
        """;
    final String upperAndLower =
        """
          public static String upper(final String s) {
            return s.toUpperCase();
          }

          public static String lower(final String s) {
            return s.toLowerCase();
          }
        """;
    final String withLower = original.replace(upperMethod, upperAndLower);
    assertThat(withLower).isNotEqualTo(original);

    final Path mainJava = ROOT.resolve("app/src/main/java/com/example/app/Main.java");
    final var mainUri = mainJava.toUri().toString();
    final String mainContent = Files.readString(mainJava);
    // Cursor immediately after "StringUtils." on the existing StringUtils.upper(...) call.
    final Position dot = findToken(mainContent, "StringUtils.upper", "upper");

    session.openDoc(mainUri, mainContent);
    session.openDoc(stringUtilsUri, original);

    assertThat(completionLabels(mainUri, dot)).contains("upper").doesNotContain("lower");

    // Saving the upstream buffer is what refreshes the mirror. Main is never re-sent, so reaching the
    // awaited label proves the downstream reschedule, not a Main keystroke. The @Timeout bounds the
    // wait for the async reschedule; exceeding it fails the test.
    saveDoc(stringUtilsUri, withLower);
    try {
      awaitCompletionLabel(mainUri, dot, "lower");
    } finally {
      saveDoc(stringUtilsUri, original);
    }
  }

  // The shapes module's tests replayed as an editor runs a module node: runnables.dir, then
  // lathe.run.test with its answer. Results, streamed events and output arrive as editors use them.
  @Test
  void runTest_shapesModule_resultsLandOnDiscoveredMethodsWithFailureLinesAndStreamedOutput()
      throws Exception {
    final Path tests = SHAPES.resolve("src/test/java/com/example/shapes");
    final Path idShapes = tests.resolve("IdShapesTest.java");
    session.openDoc(idShapes.toUri().toString(), Files.readString(idShapes));
    final List<String> landing =
        List.of(
            ID_SHAPES + "#plain()",
            ID_SHAPES + "#printsToStderr()",
            ID_SHAPES + "#overloaded(java.lang.String)",
            ID_SHAPES + "#overloaded(int)",
            ID_SHAPES + "#genericParam(java.util.List)",
            ID_SHAPES + "#arrayParam(int[])",
            ID_SHAPES + "#varargsParam(java.lang.String[])",
            ID_SHAPES + "#enumParam(com.example.shapes.IdShapesTest.Shape)",
            ID_SHAPES + "#repeated()",
            ID_SHAPES + "#factory()",
            ID_SHAPES + "$Inner#innerTest()",
            ID_SHAPES + "$Inner$Deeper#deeperTest()");
    final int assertLine =
        lineOf(Files.readAllLines(tests.resolve("AssertionsTest.java")), "assertEquals(");

    final JsonObject run =
        session
            .execute("lathe.runnables.dir", Map.of("uri", SHAPES.toUri().toString()))
            .getAsJsonObject();
    final JsonObject outcome =
        session
            .execute(
                "lathe.run.test",
                Map.of(
                    "moduleRel", run.get("moduleRel"),
                    "selections", run.get("selections"),
                    "token", "shapes-module"))
            .getAsJsonObject();
    final List<JsonObject> results = objects(outcome.getAsJsonArray("testResults"));

    assertThat(outcome.get("launched").getAsBoolean()).isTrue();
    assertThat(outcome.get("exitCode").getAsInt()).isNotZero();
    assertThat(methodIds(idShapes)).containsAll(landing);
    assertThat(results)
        .extracting(result -> result.get("positionId").getAsString())
        .containsAll(landing);
    assertThat(results)
        .filteredOn(result -> result.get("positionId").getAsString().endsWith("#singleLine()"))
        .singleElement()
        .satisfies(
            result -> {
              assertThat(result.get("status").getAsString()).isEqualTo("failed");
              assertThat(result.get("failureLine").getAsInt()).isEqualTo(assertLine);
            });

    // Every result also streamed live under this run's token, before the response.
    assertThat(streamed(client.testEvents, "shapes-module")).hasSameSizeAs(results);
    // The output as an editor shows it, streamed live: the launch header, then both streams.
    final List<JsonObject> output =
        streamed(client.testOutput, "shapes-module").stream()
            .map(params -> params.getAsJsonObject("line"))
            .toList();
    assertThat(output.subList(0, 2)).allMatch(line -> stream(line) == COMMAND);
    assertThat(text(output.get(0))).startsWith("config: ");
    assertThat(output)
        .anyMatch(line -> stream(line) == STDOUT && text(line).equals("shapes: plain on stdout"))
        .anyMatch(
            line ->
                stream(line) == STDERR
                    && text(line).equals("shapes: printsToStderr on stderr"));
  }

  private static void awaitCompletionLabel(final String uri, final Position pos, final String label)
      throws Exception {
    while (!completionLabels(uri, pos).contains(label)) {
      Thread.sleep(100);
    }
  }

  private static List<String> completionLabels(final String uri, final Position pos)
      throws Exception {
    final var params = new CompletionParams(new TextDocumentIdentifier(uri), pos);
    final var result = server.getTextDocumentService().completion(params).get(30, SECONDS);
    final List<CompletionItem> items =
        result.isLeft() ? result.getLeft() : result.getRight().getItems();
    return items.stream().map(CompletionItem::getLabel).map(LspSmokeTest::memberName).toList();
  }

  // Method items carry a "name(params)" label; the receiver-member assertions compare on name only.
  private static String memberName(final String label) {
    final int paren = label.indexOf('(');
    return (paren >= 0 ? label.substring(0, paren) : label).trim();
  }

  // As an editor saves: the file is written to disk, then the server is told.
  private static void saveDoc(final String uri, final String content) throws IOException {
    Files.writeString(Path.of(URI.create(uri)), content);
    final var params = new DidSaveTextDocumentParams();
    params.setTextDocument(new TextDocumentIdentifier(uri));
    params.setText(content);
    server.getTextDocumentService().didSave(params);
  }

  private static ClientCapabilities renameCapableClient() {
    final var workspaceEdit = new WorkspaceEditCapabilities();
    workspaceEdit.setDocumentChanges(true);
    workspaceEdit.setResourceOperations(List.of(ResourceOperationKind.Rename));
    final var workspace = new WorkspaceClientCapabilities();
    workspace.setWorkspaceEdit(workspaceEdit);
    final var capabilities = new ClientCapabilities();
    capabilities.setWorkspace(workspace);
    return capabilities;
  }

  private static List<String> symbolNames(final String query) throws Exception {
    // Over JSON-RPC the Either<SymbolInformation[], WorkspaceSymbol[]> may deserialize to either arm.
    final var result =
        server.getWorkspaceService().symbol(new WorkspaceSymbolParams(query)).get(30, SECONDS);
    if (result.isLeft()) {
      return result.getLeft().stream().map(SymbolInformation::getName).toList();
    }
    return result.getRight().stream().map(WorkspaceSymbol::getName).toList();
  }

  private static Position findToken(
      final String content, final String linePattern, final String token) {
    final String[] lines = content.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      final int patternIdx = lines[i].indexOf(linePattern);
      if (patternIdx >= 0) {
        return new Position(i, lines[i].indexOf(token, patternIdx));
      }
    }
    throw new AssertionError("pattern not found: " + linePattern);
  }

  private static List<String> methodIds(final Path file) throws Exception {
    final var uri = Map.of("uri", file.toUri().toString());
    return objects(session.execute("lathe.runnables.list", uri).getAsJsonArray()).stream()
        .filter(target -> target.get("kind").getAsInt() == TEST_METHOD)
        .map(target -> target.get("id").getAsString())
        .toList();
  }

  private static List<JsonObject> streamed(
      final List<JsonObject> notifications, final String token) {
    return notifications.stream()
        .filter(params -> params.get("token").getAsString().equals(token))
        .toList();
  }

  private static List<JsonObject> objects(final JsonArray array) {
    return array.asList().stream().map(JsonElement::getAsJsonObject).toList();
  }

  private static int stream(final JsonObject line) {
    return line.get("stream").getAsInt();
  }

  private static String text(final JsonObject line) {
    return line.get("text").getAsString();
  }

  // 1-based, as failureLine reports it.
  private static int lineOf(final List<String> lines, final String fragment) {
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains(fragment)) {
        return i + 1;
      }
    }

    throw new AssertionError("not found: " + fragment);
  }
}
