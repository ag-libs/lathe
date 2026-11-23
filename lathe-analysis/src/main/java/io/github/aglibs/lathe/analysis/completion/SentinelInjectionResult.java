package io.github.aglibs.lathe.analysis.completion;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.Optional;

// memberStubAt: where injectedContent has the semicolon that ends the sentinel, which a record body
// drops with the member it ends (see SentinelInjector); -1 where it adds none.
record SentinelInjectionResult(
    String prefix,
    int tokenStart,
    int tokenEnd,
    String receiverText,
    SentinelInjector.Context context,
    boolean hasDot,
    boolean memberReference,
    String injectedContent,
    int memberStubAt) {

  SentinelInjectionResult {
    ValidCheck.check()
        .notNull(prefix, "prefix")
        .isNonNegative(tokenStart, "tokenStart")
        .isNonNegative(tokenEnd, "tokenEnd")
        .notNull(context, "context")
        .notNull(injectedContent, "injectedContent")
        .nullOrNotEmpty(receiverText, "receiverText")
        .validate();
    ValidCheck.check()
        .assertTrue(
            memberStubAt == -1
                || memberStubAt < injectedContent.length()
                    && injectedContent.charAt(memberStubAt) == ';',
            "memberStubAt must be -1 or the semicolon's offset")
        .validate();
  }

  // Built only for the retry, so a completion that never needs it copies no text.
  Optional<SentinelInjectionResult> asMemberStub() {
    return withMemberStub(SentinelInjector.MEMBER_STUB);
  }

  Optional<SentinelInjectionResult> asMemberNameStub() {
    return withMemberStub(SentinelInjector.MEMBER_NAME_STUB);
  }

  private Optional<SentinelInjectionResult> withMemberStub(final String stub) {
    if (memberStubAt < 0) {
      return Optional.empty();
    }

    final var stubContent =
        "%s%s%s"
            .formatted(
                injectedContent.substring(0, memberStubAt),
                stub,
                injectedContent.substring(memberStubAt + 1));
    return Optional.of(
        new SentinelInjectionResult(
            prefix,
            tokenStart,
            tokenEnd,
            receiverText,
            context,
            hasDot,
            memberReference,
            stubContent,
            -1));
  }
}
