package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.typeindex.TypeKind;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompletionTypeRankingTest extends CompletionTestSupport {

  // EG-021: reactor-local types should outrank dependency/JDK types for an equal prefix match, as a
  // tiebreaker beneath match quality (mirrors IntelliJ's sdkOrLibrary/sameModule proximity
  // weighers).

  @Test
  void completion_typePrefix_ranksReactorTypeFirst() throws IOException {
    // Both real/resolvable, same (non-platform) package, both match "Certificate". The dependency
    // type has the shorter qualified name, so without the reactor boost it would sort first; the
    // reactor boost must lift the reactor type above it.
    final var index =
        CompletionFixture.typeIndex(
            tmp.resolve("index.json"),
            List.of(
                CompletionFixture.typeEntry(
                    "Certificate", "java.security.cert.Certificate", TypeKind.CLASS)),
            List.of(
                CompletionFixture.typeEntry(
                    "CertificateFactory",
                    "java.security.cert.CertificateFactory",
                    TypeKind.CLASS)));
    localFixture = new CompletionFixture(index, tmp);

    final List<String> labels =
        labels(localFixture.complete("class Test { void m() { Certificate§ } }"));

    assertThat(labels).contains("CertificateFactory", "Certificate");
    assertThat(labels.indexOf("CertificateFactory")).isLessThan(labels.indexOf("Certificate"));
  }

  @Test
  void completion_typePrefix_commonPlatformOutranksReactor() throws IOException {
    // Option A: ubiquitous platform packages (java.util) stay above reactor types, since we lack
    // the
    // usage statistics IDEs use to keep java.util types on top. The reactor-marked java.text.Format
    // must NOT outrank java.util.Formatter.
    final var index =
        CompletionFixture.typeIndex(
            tmp.resolve("index.json"),
            List.of(
                CompletionFixture.typeEntry("Formatter", "java.util.Formatter", TypeKind.CLASS)),
            List.of(CompletionFixture.typeEntry("Format", "java.text.Format", TypeKind.CLASS)));
    localFixture = new CompletionFixture(index, tmp);

    final List<String> labels =
        labels(localFixture.complete("class Test { void m() { Format§ } }"));

    assertThat(labels).contains("Formatter", "Format");
    assertThat(labels.indexOf("Formatter")).isLessThan(labels.indexOf("Format"));
  }

  @Test
  void completion_typePrefix_javaLangSubpackageDoesNotOutrankReactor() throws IOException {
    // Only the exact java.lang package is the ubiquitous auto-imported tier; subpackages (reflect,
    // management, invoke, …) are specialized and must rank as ordinary JDK entries — below reactor.
    final var index =
        CompletionFixture.typeIndex(
            tmp.resolve("index.json"),
            List.of(
                CompletionFixture.typeEntry("Proxy", "java.lang.reflect.Proxy", TypeKind.CLASS)),
            List.of(
                CompletionFixture.typeEntry("Provider", "java.security.Provider", TypeKind.CLASS)));
    localFixture = new CompletionFixture(index, tmp);

    final List<String> labels = labels(localFixture.complete("class Test { void m() { Pro§ } }"));

    assertThat(labels).contains("Provider", "Proxy");
    assertThat(labels.indexOf("Provider")).isLessThan(labels.indexOf("Proxy"));
  }
}
