package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextMentionsTest {

  @TempDir private Path tmp;

  // Code is the rename's; a literal naming the symbol (alone or qualified) is text the agent
  // reviews,
  // prose that merely contains the word is not.
  @Test
  void inStringLiterals_literalNamingIt_notCodeProseOrALongerWord() throws Exception {
    final Path source =
        Files.writeString(
            tmp.resolve("Shop.java"),
            """
            class Shop {
              void sell() {}
              String key = "sell";
              String other = "reselling sellAll";
              String prose = "we sell shoes";
              Object reflect() { return "com.example.Shop#sell"; }
              String query = \"""
                  call com.example.Shop.sell
                  \""";
            }
            """);

    assertThat(TextMentions.inStringLiterals(List.of(source), "sell"))
        .extracting(location -> location.getRange().getStart().getLine())
        .as("a text block's line has no quote of its own")
        .containsExactly(2, 5, 6);
  }

  // A resource names a class qualified, or by its file name as a service file does.
  @Test
  void inResources_qualifiedNameOrServiceFile_notAnUnqualifiedWord() throws Exception {
    final Path resources = Files.createDirectories(tmp.resolve("resources"));
    Files.createDirectories(resources.resolve("META-INF/services"));
    Files.writeString(
        resources.resolve("META-INF/services/com.example.Shop"), "com.example.Impl\n");
    Files.writeString(
        resources.resolve("beans.xml"),
        "<bean class=\"com.example.Shop\"/>\n<!-- Shop is fine -->\n<bean class=\"com.example.ShopKeeper\"/>\n");

    Files.writeString(resources.resolve("app.Shop"), "not a service file\n");

    final List<Location> mentions = TextMentions.inResources(List.of(resources), "Shop");

    assertThat(mentions)
        .extracting(location -> tmp.relativize(Path.of(URI.create(location.getUri()))))
        .containsExactlyInAnyOrder(
            Path.of("resources/META-INF/services/com.example.Shop"),
            Path.of("resources/beans.xml"));
    assertThat(TextMentions.inResources(List.of(tmp.resolve("missing")), "Shop")).isEmpty();
  }
}
