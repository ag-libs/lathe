package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.Scope;
import io.github.aglibs.lathe.server.analysis.FileAnalysis;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

final class ProposalGenerator {

  private static final Logger LOG = Logger.getLogger(ProposalGenerator.class.getName());

  private final FileAnalysis snapshot;
  private final Types types;
  private final CompletionItemFactory itemFactory;

  ProposalGenerator(final FileAnalysis snapshot) {
    this.snapshot = snapshot;
    this.types = snapshot.types();
    this.itemFactory = new CompletionItemFactory(types);
  }

  List<CompletionItem> proposeMemberAccess(
      final TypeMirror receiverType,
      final String prefix,
      final boolean isStaticAccess,
      final Scope scope) {
    if (!(receiverType instanceof final DeclaredType declaredType)) {
      return List.of();
    }

    final var element = types.asElement(declaredType);
    if (!(element instanceof final TypeElement typeEl)) {
      return List.of();
    }

    return snapshot.elements().getAllMembers(typeEl).stream()
        .filter(
            el ->
                el.getKind() == ElementKind.METHOD
                    || el.getKind() == ElementKind.FIELD
                    || el.getKind() == ElementKind.ENUM_CONSTANT)
        .filter(el -> !isStaticAccess || el.getModifiers().contains(Modifier.STATIC))
        .filter(el -> isAccessible(el, declaredType, scope))
        .filter(el -> el.getSimpleName().toString().startsWith(prefix))
        .map(
            el -> {
              final var item = itemFactory.member(el, declaredType);
              item.setSortText(sortKey(el));
              return item;
            })
        .toList();
  }

  List<CompletionItem> proposeNestedTypes(final TypeElement outer, final String prefix) {
    return outer.getEnclosedElements().stream()
        .filter(
            el ->
                el.getKind() == ElementKind.CLASS
                    || el.getKind() == ElementKind.INTERFACE
                    || el.getKind() == ElementKind.ENUM
                    || el.getKind() == ElementKind.RECORD)
        .filter(el -> el.getSimpleName().toString().startsWith(prefix))
        .map(
            el -> {
              final var name = el.getSimpleName().toString();
              final var item = new CompletionItem();
              item.setLabel(name);
              item.setInsertText(name);
              item.setFilterText(name);
              item.setKind(
                  el.getKind() == ElementKind.INTERFACE
                      ? CompletionItemKind.Interface
                      : CompletionItemKind.Class);
              return item;
            })
        .toList();
  }

  List<CompletionItem> proposeSimpleName(
      final String enclosingClass,
      final String enclosingMethod,
      final String prefix,
      final int cursorOffset) {
    final var context =
        new SimpleNameProposalContext(enclosingClass, enclosingMethod, prefix, cursorOffset);
    return new SimpleNameProposalCollector(snapshot, itemFactory, context).collect();
  }

  private static String sortKey(final Element el) {
    final var declaring = el.getEnclosingElement();
    final boolean isObjectMember =
        declaring instanceof TypeElement te
            && te.getQualifiedName().contentEquals("java.lang.Object");
    return (isObjectMember ? "9_" : "0_") + el.getSimpleName();
  }

  private boolean isAccessible(
      final Element el, final DeclaredType receiverType, final Scope scope) {
    if (scope == null) {
      return true;
    }

    try {
      return snapshot.trees().isAccessible(scope, el, receiverType);
    } catch (final IllegalArgumentException e) {
      LOG.log(
          Level.FINE,
          e,
          () ->
              "[proposal] isAccessible failed for %s on %s"
                  .formatted(el.getSimpleName(), receiverType));
      return true;
    }
  }
}
