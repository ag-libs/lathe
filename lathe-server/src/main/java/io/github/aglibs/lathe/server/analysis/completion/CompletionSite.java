package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.validcheck.ValidCheck;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

record CompletionSite(
    String uri,
    String prefix,
    int cursorOffset,
    Range replacementRange,
    SentinelContext sentinelContext,
    CompletionMode mode,
    SentinelInjector.Context injectorContext,
    String receiverText,
    int receiverEndOffset,
    String enclosingClass,
    String enclosingMethod,
    int argIndex,
    String enclosingReceiver,
    String enclosingMethodName,
    String declaredTypeText) {

  CompletionSite {
    ValidCheck.check()
        .notBlank(uri, "uri")
        .notNull(prefix, "prefix")
        .isNonNegative(cursorOffset, "cursorOffset")
        .notNull(replacementRange, "replacementRange")
        .notNull(sentinelContext, "sentinelContext")
        .notNull(mode, "mode")
        .notNull(injectorContext, "injectorContext")
        .nullOrNotEmpty(receiverText, "receiverText")
        .validate();
  }

  static CompletionSite from(
      final CompletionRequest request, final SentinelResult injected, final ParsedSentinel parsed) {
    return new CompletionSite(
        request.uri(),
        injected.prefix(),
        request.cursorOffset(),
        replacementRange(
            request.pos(), injected.prefix(), injected.tokenEnd() - request.cursorOffset()),
        parsed.sentinelContext(),
        mode(injected, parsed),
        injected.context(),
        parsed.receiverText(),
        parsed.receiverEndOffset(),
        parsed.enclosingClass(),
        parsed.enclosingMethod(),
        parsed.argIndex(),
        parsed.enclosingReceiver(),
        parsed.enclosingMethodName(),
        parsed.declaredTypeText());
  }

  private static Range replacementRange(
      final Position cursor, final String prefix, final int suffixLength) {
    final var start = new Position(cursor.getLine(), cursor.getCharacter() - prefix.length());
    final var end = new Position(cursor.getLine(), cursor.getCharacter() + suffixLength);
    return new Range(start, end);
  }

  private static CompletionMode mode(final SentinelResult injected, final ParsedSentinel parsed) {
    return switch (parsed.sentinelContext()) {
      case IMPORT -> CompletionMode.IMPORT;
      case STATIC_IMPORT -> CompletionMode.STATIC_IMPORT;
      case MEMBER_ACCESS, LAMBDA_BODY -> CompletionMode.MEMBER;
      case TYPE_REFERENCE, VARIABLE_DECLARATION -> CompletionMode.TYPE;
      case CONSTRUCTOR_CALL ->
          parsed.argIndex() >= 0 ? simpleNameMode(injected) : CompletionMode.TYPE;
      case SIMPLE_NAME, ARGUMENT_POSITION, CASE_LABEL -> simpleNameMode(injected);
      default -> CompletionMode.KEYWORD_ONLY;
    };
  }

  private static CompletionMode simpleNameMode(final SentinelResult injected) {
    return !injected.prefix().isEmpty() && Character.isUpperCase(injected.prefix().charAt(0))
        ? CompletionMode.MIXED
        : CompletionMode.VALUE;
  }
}
