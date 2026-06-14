package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

class JavadocMarkdownPrinterTest {

  // --- format: plain text and null ---

  @Test
  void format_null_returnsEmpty() {
    assertThat(JavadocMarkdownPrinter.format(null)).isEmpty();
  }

  @Test
  void format_plainText_returnsTrimmedText() throws IOException {
    final var tree = parseMethodDoc("class T { /** Plain text only. */ void m() {} }");
    assertThat(JavadocMarkdownPrinter.format(tree)).isEqualTo("Plain text only.");
  }

  // --- format: HTML inline elements ---

  @Test
  void format_boldHtml_convertsToMarkdown() throws IOException {
    final var tree = parseMethodDoc("class T { /** A <b>bold</b> word. */ void m() {} }");
    assertThat(JavadocMarkdownPrinter.format(tree)).isEqualTo("A **bold** word.");
  }

  @Test
  void format_strongHtml_convertsToMarkdown() throws IOException {
    final var tree = parseMethodDoc("class T { /** A <strong>word</strong>. */ void m() {} }");
    assertThat(JavadocMarkdownPrinter.format(tree)).contains("**word**");
  }

  @Test
  void format_italicHtml_convertsToMarkdown() throws IOException {
    final var tree = parseMethodDoc("class T { /** An <i>italic</i> word. */ void m() {} }");
    assertThat(JavadocMarkdownPrinter.format(tree)).isEqualTo("An *italic* word.");
  }

  @Test
  void format_codeHtml_convertsToMarkdown() throws IOException {
    final var tree = parseMethodDoc("class T { /** Use <code>null</code>. */ void m() {} }");
    assertThat(JavadocMarkdownPrinter.format(tree)).isEqualTo("Use `null`.");
  }

  @Test
  void format_preHtml_convertsToPre() throws IOException {
    final var tree = parseMethodDoc("class T { /** <pre>code block</pre> */ void m() {} }");
    final String result = JavadocMarkdownPrinter.format(tree);
    assertThat(result).contains("```").contains("code block");
  }

  // --- format: inline tags ---

  @Test
  void format_inlineCodeTag_convertsToBacktick() throws IOException {
    final var tree = parseMethodDoc("class T { /** Use {@code null}. */ void m() {} }");
    assertThat(JavadocMarkdownPrinter.format(tree)).isEqualTo("Use `null`.");
  }

  @Test
  void format_inlineLiteralTag_passesThrough() throws IOException {
    final var tree = parseMethodDoc("class T { /** Use {@literal <T>}. */ void m() {} }");
    assertThat(JavadocMarkdownPrinter.format(tree)).isEqualTo("Use <T>.");
  }

  @Test
  void format_linkTagWithoutLabel_rendersAsCode() throws IOException {
    final var tree = parseMethodDoc("class T { /** See {@link String#length()}. */ void m() {} }");
    assertThat(JavadocMarkdownPrinter.format(tree)).contains("`String.length()`");
  }

  @Test
  void format_linkTagWithLabel_rendersLabel() throws IOException {
    final var tree =
        parseMethodDoc("class T { /** See {@link String#length() the length}. */ void m() {} }");
    assertThat(JavadocMarkdownPrinter.format(tree)).contains("the length");
  }

  @Test
  void format_linkplainTag_rendersWithoutBackticks() throws IOException {
    final var tree = parseMethodDoc("class T { /** See {@linkplain String#trim}. */ void m() {} }");
    final String result = JavadocMarkdownPrinter.format(tree);
    assertThat(result).contains("String.trim");
    assertThat(result).doesNotContain("`String.trim`");
  }

  // --- format: HTML entities ---

  @Test
  void format_htmlEntity_decoded() throws IOException {
    final var tree = parseMethodDoc("class T { /** Use &lt;T&gt; and &amp;. */ void m() {} }");
    assertThat(JavadocMarkdownPrinter.format(tree)).isEqualTo("Use <T> and &.");
  }

  // --- format: block tags (require multi-line javadoc) ---

  @Test
  void format_paramBlockTag_formattedWithBold() throws IOException {
    final var tree =
        parseMethodDoc(
            """
            class T {
              /**
               * Desc.
               * @param x the x value
               */
              void m(int x) {}
            }
            """);
    final String result = JavadocMarkdownPrinter.format(tree);
    assertThat(result).contains("Desc.");
    assertThat(result).contains("**@param** `x` the x value");
  }

  @Test
  void format_returnBlockTag_formattedWithBold() throws IOException {
    final var tree =
        parseMethodDoc(
            """
            class T {
              /**
               * Greet.
               * @return the greeting
               */
              String m() { return ""; }
            }
            """);
    final String result = JavadocMarkdownPrinter.format(tree);
    assertThat(result).contains("Greet.");
    assertThat(result).contains("**@return** the greeting");
  }

  @Test
  void format_throwsBlockTag_formattedWithBold() throws IOException {
    final var tree =
        parseMethodDoc(
            """
            class T {
              /**
               * Op.
               * @throws IllegalArgumentException if invalid
               */
              void m() {}
            }
            """);
    assertThat(JavadocMarkdownPrinter.format(tree))
        .contains("**@throws** `IllegalArgumentException` if invalid");
  }

  @Test
  void format_multipleBlockTags_allIncluded() throws IOException {
    final var tree =
        parseMethodDoc(
            """
            class T {
              /**
               * Main desc.
               * @param a the a
               * @param b the b
               * @return result
               */
              int m(int a, int b) { return 0; }
            }
            """);
    final String result = JavadocMarkdownPrinter.format(tree);
    assertThat(result).contains("Main desc.");
    assertThat(result).contains("**@param** `a` the a");
    assertThat(result).contains("**@param** `b` the b");
    assertThat(result).contains("**@return** result");
  }

  @Test
  void format_typeParamTag_excluded() throws IOException {
    final var tree =
        parseMethodDoc(
            """
            class T {
              /**
               * Desc.
               * @param <E> element type
               */
              void m() {}
            }
            """);
    assertThat(JavadocMarkdownPrinter.format(tree)).doesNotContain("@param");
  }

  // --- mainDescription ---

  @Test
  void mainDescription_null_returnsEmpty() {
    assertThat(JavadocMarkdownPrinter.mainDescription(null)).isEmpty();
  }

  @Test
  void mainDescription_excludesBlockTags() throws IOException {
    final var tree =
        parseMethodDoc(
            """
            class T {
              /**
               * Says hello.
               * @param msg the msg
               * @return result
               */
              String m() { return ""; }
            }
            """);
    final String desc = JavadocMarkdownPrinter.mainDescription(tree);
    assertThat(desc).contains("Says hello.");
    assertThat(desc).doesNotContain("@param");
    assertThat(desc).doesNotContain("@return");
  }

  @Test
  void mainDescription_htmlFormatted() throws IOException {
    final var tree =
        parseMethodDoc(
            """
            class T {
              /**
               * Returns <b>bold</b> text.
               * @return it
               */
              String m() { return ""; }
            }
            """);
    assertThat(JavadocMarkdownPrinter.mainDescription(tree)).isEqualTo("Returns **bold** text.");
  }

  // --- paramDocs ---

  @Test
  void paramDocs_null_returnsEmpty() {
    assertThat(JavadocMarkdownPrinter.paramDocs(null)).isEmpty();
  }

  @Test
  void paramDocs_singleParam_returnsMap() throws IOException {
    final var tree =
        parseMethodDoc(
            """
            class T {
              /**
               * Desc.
               * @param x the x
               */
              void m(int x) {}
            }
            """);
    assertThat(JavadocMarkdownPrinter.paramDocs(tree)).containsEntry("x", "the x");
  }

  @Test
  void paramDocs_multipleParams_returnsAllEntries() throws IOException {
    final var tree =
        parseMethodDoc(
            """
            class T {
              /**
               * Desc.
               * @param name the recipient
               * @param count repetitions
               */
              void m(String name, int count) {}
            }
            """);
    final var docs = JavadocMarkdownPrinter.paramDocs(tree);
    assertThat(docs).containsEntry("name", "the recipient");
    assertThat(docs).containsEntry("count", "repetitions");
  }

  @Test
  void paramDocs_noParamTags_returnsEmpty() throws IOException {
    final var tree =
        parseMethodDoc(
            """
            class T {
              /**
               * Just a description.
               * @return x
               */
              int m() { return 0; }
            }
            """);
    assertThat(JavadocMarkdownPrinter.paramDocs(tree)).isEmpty();
  }

  @Test
  void paramDocs_typeParamIgnored() throws IOException {
    final var tree =
        parseMethodDoc(
            """
            class T {
              /**
               * Desc.
               * @param <E> type
               * @param x the x
               */
              void m(int x) {}
            }
            """);
    final var docs = JavadocMarkdownPrinter.paramDocs(tree);
    assertThat(docs).containsOnlyKeys("x");
    assertThat(docs).containsEntry("x", "the x");
  }

  @Test
  void paramDocs_richDescriptionFormatted() throws IOException {
    final var tree =
        parseMethodDoc(
            """
            class T {
              /**
               * Desc.
               * @param x the {@code x} value
               */
              void m(int x) {}
            }
            """);
    assertThat(JavadocMarkdownPrinter.paramDocs(tree).get("x")).contains("`x`");
  }

  // --- helpers ---

  private static DocCommentTree parseMethodDoc(final String src) throws IOException {
    final var jfo =
        new SimpleJavaFileObject(URI.create("test://T.java"), JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return src;
          }
        };
    final var task =
        (JavacTask)
            ToolProvider.getSystemJavaCompiler()
                .getTask(null, null, null, null, null, List.of(jfo));
    final CompilationUnitTree cu = task.parse().iterator().next();
    final DocTrees docTrees = DocTrees.instance(task);
    final var classTree = (ClassTree) cu.getTypeDecls().getFirst();
    for (final var member : classTree.getMembers()) {
      final var path = TreePath.getPath(cu, member);
      final var tree = docTrees.getDocCommentTree(path);
      if (tree != null) {
        return tree;
      }
    }
    return null;
  }
}
