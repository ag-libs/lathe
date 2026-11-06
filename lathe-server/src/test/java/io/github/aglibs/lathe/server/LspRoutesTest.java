package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.analysis.ExtractionSupport;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DeclarationParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams;
import org.eclipse.lsp4j.DocumentRangesFormattingParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.TypeHierarchyPrepareParams;
import org.eclipse.lsp4j.TypeHierarchySupertypesParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;

// Each textDocument request through the service and the event loop to its feature and back, over
// a real workspace: the features are covered on their own, this covers the way to them.
class LspRoutesTest extends ServiceTestBase {

  @Test
  void navigationRequests_onTheShop_eachAnswerComesFromTheWorkspace() throws Exception {
    final ShopFixture shop = openShop();
    final var call = documentOf(shop.use());
    final var sell = positionOf(shop.use(), "sell(1)");

    assertThat(
            files(
                service
                    .definition(new DefinitionParams(call, sell))
                    .get(TIMEOUT_S, TimeUnit.SECONDS)))
        .containsExactly("Shop.java");
    assertThat(
            files(
                service
                    .declaration(new DeclarationParams(call, sell))
                    .get(TIMEOUT_S, TimeUnit.SECONDS)))
        .as("the declaration Shop.sell overrides")
        .containsExactly("Api.java");
    final var onApi =
        new ImplementationParams(documentOf(shop.api()), positionOf(shop.api(), "sell"));
    assertThat(files(service.implementation(onApi).get(TIMEOUT_S, TimeUnit.SECONDS)))
        .as("a request answers only for an open document")
        .isEmpty();
    openDoc(shop.api(), Files.readString(shop.api()));
    awaitWorkerIdle();
    assertThat(files(service.implementation(onApi).get(TIMEOUT_S, TimeUnit.SECONDS)))
        .containsExactly("Shop.java");

    final List<? extends DocumentHighlight> highlights =
        service
            .documentHighlight(new DocumentHighlightParams(call, sell))
            .get(TIMEOUT_S, TimeUnit.SECONDS);
    assertThat(highlights)
        .as("both calls in Use, on the return line")
        .extracting(highlight -> highlight.getRange().getStart().getLine())
        .containsExactly(4, 4);

    final List<TypeHierarchyItem> prepared =
        service
            .prepareTypeHierarchy(
                new TypeHierarchyPrepareParams(call, positionOf(shop.use(), "Shop shop")))
            .get(TIMEOUT_S, TimeUnit.SECONDS);
    assertThat(prepared).extracting(TypeHierarchyItem::getName).containsExactly("Shop");
    assertThat(
            service
                .typeHierarchySupertypes(new TypeHierarchySupertypesParams(prepared.get(0)))
                .get(TIMEOUT_S, TimeUnit.SECONDS))
        .extracting(TypeHierarchyItem::getName)
        .contains("Api");
  }

  @Test
  void editingRequests_inAnOpenFile_completeSignAndActWithResolve() throws Exception {
    final ShopFixture shop = openShop();
    final var use = documentOf(shop.use());

    final var completion = new CompletionParams(use, positionOf(shop.use(), "ell(1)"));
    final Either<List<CompletionItem>, CompletionList> items =
        service.completion(completion).get(TIMEOUT_S, TimeUnit.SECONDS);
    final List<CompletionItem> offered =
        items.isLeft() ? items.getLeft() : items.getRight().getItems();
    assertThat(offered)
        .extracting(CompletionItem::getLabel)
        .anyMatch(label -> label.startsWith("sell"));

    assertThat(
            service
                .signatureHelp(new SignatureHelpParams(use, positionOf(shop.use(), "1)")))
                .get(TIMEOUT_S, TimeUnit.SECONDS)
                .getSignatures())
        .singleElement()
        .satisfies(signature -> assertThat(signature.getLabel()).contains("sell(int count)"));

    // Extract method is checked by compiling it before it is offered, so a client that resolves
    // gets it without an edit and resolves it.
    service.setCodeActionResolveSupported(true);
    final var selection =
        new Range(positionOf(shop.use(), "shop.sell(1)"), positionOf(shop.use(), " + shop"));
    final var context = new CodeActionContext(List.of());
    context.setOnly(List.of(CodeActionKind.Refactor));
    final List<CodeAction> refactors =
        service
            .codeAction(new CodeActionParams(use, selection, context))
            .get(TIMEOUT_S, TimeUnit.SECONDS)
            .stream()
            .filter(Either::isRight)
            .map(Either::getRight)
            .toList();
    assertThat(refactors)
        .extracting(CodeAction::getTitle)
        .anyMatch(title -> title.startsWith("Extract variable"));
    final CodeAction unresolved =
        refactors.stream()
            .filter(action -> ExtractionSupport.METHOD_KIND.equals(action.getKind()))
            .findFirst()
            .orElseThrow();
    assertThat(unresolved.getEdit()).isNull();
    assertThat(unresolved.getData()).isNotNull();
    final CodeAction resolved =
        service.resolveCodeAction(unresolved).get(TIMEOUT_S, TimeUnit.SECONDS);
    assertThat(resolved.getDisabled()).isNull();
    assertThat(resolved.getEdit().getChanges().get(use.getUri()))
        .extracting(TextEdit::getNewText)
        .anyMatch(text -> text.contains("private int"));
  }

  @Test
  @EnabledForJreRange(minVersion = FormatterFixtures.GOOGLE_JAVA_FORMAT_MIN_JDK)
  void formattingRequests_withAFormatter_rangeFormatsItsLinesOnly_onTypeAnswersNothingYet()
      throws Exception {
    final ShopFixture shop = openShop();
    service.setFormatEngine(FormatterFixtures.googleJavaFormat());
    final String misindented =
        "package com.example;\nclass Use {\n  int run() {\n final Shop shop = new Shop();\n"
            + "    return shop.sell(1) + shop.sell(2);\n  }\n}\n";
    change(shop.use(), misindented, 2);
    awaitWorkerIdle();
    final var use = documentOf(shop.use());
    final var options = new FormattingOptions(2, true);
    final var line = new Range(new Position(3, 0), new Position(4, 0));

    final List<? extends TextEdit> edits =
        service
            .rangesFormatting(new DocumentRangesFormattingParams(use, options, List.of(line)))
            .get(TIMEOUT_S, TimeUnit.SECONDS);

    assertThat(edits)
        .isNotEmpty()
        .allSatisfy(edit -> assertThat(edit.getRange().getStart().getLine()).isEqualTo(3));
    // On-type formatting is not implemented: it answers no edits, so typing changes nothing.
    assertThat(
            service
                .onTypeFormatting(
                    new DocumentOnTypeFormattingParams(use, options, new Position(5, 3), "}"))
                .get(TIMEOUT_S, TimeUnit.SECONDS))
        .isEmpty();
  }

  // A formatter that runs a command (a build, say) waits on its process off the event loop: the
  // server answers other requests meanwhile, and the edits arrive once the process ends.
  @Test
  void formatting_commandFormatterStillRunning_otherRequestsAnswered_editsArriveWhenItEnds()
      throws Exception {
    final ShopFixture shop = openShop();
    final Path gate = tmp.resolve("gate");
    service.setFormatEngine(
        new FileCommandFormatEngine(
            TestProcess.command("await", gate.toString(), "%FILE%"),
            tmp,
            FileCommandFormatEngine.DEFAULT_TIMEOUT));

    final var formatting =
        service.formatting(
            new DocumentFormattingParams(documentOf(shop.use()), new FormattingOptions(2, true)));
    awaitWorkerIdle();

    assertThat(formatting).as("the formatter waits for the gate").isNotDone();
    Files.writeString(gate, "");
    assertThat(formatting.get(TIMEOUT_S, TimeUnit.SECONDS))
        .singleElement()
        .satisfies(edit -> assertThat(edit.getNewText()).contains("SHOP"));
  }

  // Closing the server while a command formatter runs answers the request (no edits) instead of
  // leaving it waiting, and stops the formatter's process.
  @Test
  void formatting_serverClosedWhileTheFormatterRuns_requestAnsweredWithNoEdits() throws Exception {
    final ShopFixture shop = openShop();
    service.setFormatEngine(
        new FileCommandFormatEngine(
            TestProcess.command("await", tmp.resolve("never").toString(), "%FILE%"),
            tmp,
            FileCommandFormatEngine.DEFAULT_TIMEOUT));
    final var formatting =
        service.formatting(
            new DocumentFormattingParams(documentOf(shop.use()), new FormattingOptions(2, true)));
    awaitWorkerIdle();

    service.close();

    assertThat(formatting.get(TIMEOUT_S, TimeUnit.SECONDS)).isEmpty();
  }

  private static List<String> files(
      final Either<List<? extends Location>, List<? extends LocationLink>> locations) {
    final List<String> uris =
        locations.isLeft()
            ? locations.getLeft().stream().map(Location::getUri).toList()
            : locations.getRight().stream().map(LocationLink::getTargetUri).toList();
    return uris.stream().map(uri -> Path.of(URI.create(uri)).getFileName().toString()).toList();
  }
}
