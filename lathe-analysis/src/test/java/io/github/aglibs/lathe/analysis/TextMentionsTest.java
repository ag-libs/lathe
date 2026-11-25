package io.github.aglibs.lathe.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
                  we sell, then
                  call com.example.Shop.sell
                  \""";
            }
            """);

    assertThat(TextMentions.inStringLiterals(List.of(source), "sell"))
        .extracting(
            location -> location.getRange().getStart().getLine(),
            location -> location.getRange().getStart().getCharacter())
        .as("at the name that makes it a mention, in a text block on its own line")
        .containsExactly(tuple(2, 16), tuple(5, 46), tuple(8, 28));
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

  // A folder listed twice is read once, and a match is at its name: a review row at column 1 with
  // a twin sends the agent hunting (FR-040).
  @Test
  void inResources_folderListedTwiceAndMatchMidLine_oncePerMatchAtItsColumn() throws Exception {
    final Path resources = Files.createDirectories(tmp.resolve("templates"));
    Files.writeString(resources.resolve("list.html"), "<td th:text=\"${owner.city}\" />\n");

    assertThat(TextMentions.inResources(List.of(resources, resources), "city"))
        .extracting(
            location -> location.getRange().getStart().getLine(),
            location -> location.getRange().getStart().getCharacter())
        .containsExactly(tuple(0, 21));
  }

  // A file saved with a byte order mark counts columns from after it; a literal in a Java folder a
  // build also declares as a resource root is listed once, not once per scan.
  @Test
  void find_byteOrderMarkAndJavaFolderAsResourceRoot_eachPlaceOnceAtItsColumn() throws Exception {
    final Path folder = Files.createDirectories(tmp.resolve("java"));
    Files.writeString(folder.resolve("beans.xml"), "\uFEFF<bean class=\"com.example.Shop\"/>\n");
    final Path source =
        Files.writeString(
            folder.resolve("Shop.java"), "class Shop { String c = \"com.example.Shop\"; }\n");

    assertThat(
            TextMentions.find(new TextMentions.Sources(List.of(source), List.of(folder)), "Shop"))
        .extracting(
            location -> Path.of(URI.create(location.getUri())).getFileName().toString(),
            location -> location.getRange().getStart().getCharacter())
        .containsExactlyInAnyOrder(tuple("beans.xml", 25), tuple("Shop.java", 37));
  }
}
