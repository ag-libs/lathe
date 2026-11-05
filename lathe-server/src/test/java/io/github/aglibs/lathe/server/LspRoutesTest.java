package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DeclarationParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams;
import org.eclipse.lsp4j.DocumentRangesFormattingParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.TypeHierarchyPrepareParams;
import org.eclipse.lsp4j.TypeHierarchySupertypesParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

// Each textDocument request through the service and the event loop to its feature and back, over
// a real workspace: the features are covered on their own, this covers the way to them.
class LspRoutesTest extends ServiceTestBase {

  private static final long TIMEOUT_S = 5;

  @Test
  void navigationRoutes_onTheShop_eachAnswerComesFromTheWorkspace() throws Exception {
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
    assertThat(highlights).as("both calls in Use").hasSize(2);

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
  void editingRoutes_inAnOpenFile_completeSignActAndFormat() throws Exception {
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

    final var selection =
        new Range(positionOf(shop.use(), "shop.sell(1)"), positionOf(shop.use(), " + shop"));
    final var context = new CodeActionContext(List.of());
    context.setOnly(List.of(CodeActionKind.Refactor));
    final List<Either<Command, CodeAction>> actions =
        service
            .codeAction(new CodeActionParams(use, selection, context))
            .get(TIMEOUT_S, TimeUnit.SECONDS);
    final List<CodeAction> refactors =
        actions.stream().filter(Either::isRight).map(Either::getRight).toList();
    assertThat(refactors)
        .extracting(CodeAction::getTitle)
        .anyMatch(title -> title.startsWith("Extract variable"));
    final CodeAction resolved =
        service.resolveCodeAction(refactors.get(0)).get(TIMEOUT_S, TimeUnit.SECONDS);
    assertThat(resolved.getEdit()).as("a resolved action carries its edit").isNotNull();

    final var options = new FormattingOptions(2, true);
    assertThat(
            service
                .onTypeFormatting(
                    new DocumentOnTypeFormattingParams(
                        use, options, positionOf(shop.use(), "}"), "}"))
                .get(TIMEOUT_S, TimeUnit.SECONDS))
        .as("formatting is off in this workspace")
        .isEmpty();
    assertThat(
            service
                .rangesFormatting(
                    new DocumentRangesFormattingParams(use, options, List.of(selection)))
                .get(TIMEOUT_S, TimeUnit.SECONDS))
        .isEmpty();
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
