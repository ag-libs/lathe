package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.server.analysis.FileAnalysis;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

final class MemberResolver {

  private MemberResolver() {}

  static List<CompletionItem> membersOf(
      final DeclaredType declaredType, final FileAnalysis analysis) {
    final var typeElement = (TypeElement) declaredType.asElement();
    return analysis.elements().getAllMembers(typeElement).stream()
        .filter(m -> m.getKind() != ElementKind.CONSTRUCTOR)
        .map(m -> toItem(m, declaredType, analysis))
        .toList();
  }

  private static CompletionItem toItem(
      final Element member, final DeclaredType receiverType, final FileAnalysis analysis) {
    final var item = new CompletionItem(member.getSimpleName().toString());
    item.setKind(completionKind(member.getKind()));
    item.setDetail(memberDetail(member, receiverType, analysis));
    return item;
  }

  private static String memberDetail(
      final Element member, final DeclaredType receiverType, final FileAnalysis analysis) {
    try {
      final TypeMirror memberType = analysis.types().asMemberOf(receiverType, member);
      if (memberType instanceof ExecutableType execType) {
        return execType.getReturnType().toString();
      }

      return memberType.toString();
    } catch (final IllegalArgumentException e) {
      return member.asType().toString();
    }
  }

  private static CompletionItemKind completionKind(final ElementKind kind) {
    return switch (kind) {
      case METHOD -> CompletionItemKind.Method;
      case FIELD -> CompletionItemKind.Field;
      case CLASS, RECORD, INTERFACE -> CompletionItemKind.Class;
      case ENUM -> CompletionItemKind.Enum;
      case ENUM_CONSTANT -> CompletionItemKind.EnumMember;
      default -> CompletionItemKind.Text;
    };
  }
}
