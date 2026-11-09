package io.github.aglibs.lathe.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.analysis.JavadocMarkdownPrinter.DocChain;
import io.github.aglibs.lathe.analysis.JavadocMarkdownPrinter.MethodDoc;
import io.github.aglibs.lathe.analysis.JavadocMarkdownPrinter.SignatureDocs;
import io.github.aglibs.lathe.analysis.testing.TestJavac;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
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

  // --- format: paragraphs and anchors ---

  @Test
  void format_paragraphTags_collapseToOneBlankLine() throws IOException {
    final var tree =
        parseMethodDoc(
            """
            class T {
              /**
               * First.
               * <p>
               * Second.
               *
               * <p>Third.</p>
               * <p>
               * <p>Fourth.
               * @return the result
               */
              int m() { return 0; }
            }
            """);
    assertThat(JavadocMarkdownPrinter.format(tree))
        .isEqualTo("First.\n\nSecond.\n\nThird.\n\nFourth.\n\n**@return** the result");
  }

  @Test
  void format_anchorWithHref_rendersOneLineMarkdownLink() throws IOException {
    final var tree =
        parseMethodDoc(
            """
            class T {
              /**
               * See <a href="https://example.com/a">the
               *     guide</a> here.
               *
               * @see <a href="https://example.com/b">Other
               *     page</a>
               */
              void m() {}
            }
            """);
    assertThat(JavadocMarkdownPrinter.format(tree))
        .isEqualTo(
            "See [the guide](https://example.com/a) here.\n\n"
                + "**@see** [Other page](https://example.com/b)");
  }

  @Test
  void format_anchorEdgeCases_escapeParensDropDocRootAndCloseUnclosed() throws IOException {
    final var tree =
        parseMethodDoc(
            """
            class T {
              /**
               * A <a href="https://example.com/Foo_(bar)">wiki</a> page,
               * a <a href="{@docRoot}/x.html">local</a> page,
               * and an <a href="https://example.com/c">unclosed link.
               *
               * @param v the value
               * @see <a href="https://example.com/d">Last</a>
               */
              void m(int v) {}
            }
            """);
    assertThat(JavadocMarkdownPrinter.format(tree))
        .contains("[wiki](https://example.com/Foo_%28bar%29)")
        .contains("a local page")
        .doesNotContain("docRoot")
        .contains("[unclosed link.](https://example.com/c)")
        .contains("**@param** `v` the value")
        .contains("**@see** [Last](https://example.com/d)");
  }

  @Test
  void format_linkToMember_readsAsMemberOrTypeDotMember() throws IOException {
    final var tree =
        parseMethodDoc(
            "class T { /** Use {@link #other()} or {@link String#isEmpty()}. */ void m() {} }");
    assertThat(JavadocMarkdownPrinter.format(tree))
        .isEqualTo("Use `other()` or `String.isEmpty()`.");
  }

  @Test
  void format_anchorWithoutHref_printsLabelOnly() throws IOException {
    final var tree = parseMethodDoc("class T { /** An <a id=\"x\">anchor</a>. */ void m() {} }");
    assertThat(JavadocMarkdownPrinter.format(tree)).isEqualTo("An anchor.");
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

  // javac splits the first sentence from the rest and drops the whitespace between them.
  @Test
  void format_multiSentenceDescription_keepsSpaceBetweenSentences() throws IOException {
    final var tree =
        parseMethodDoc("class T { /** Factory method. For general use. */ void m() {} }");
    assertThat(JavadocMarkdownPrinter.format(tree)).isEqualTo("Factory method. For general use.");
    assertThat(signatureDocs(tree).description()).isEqualTo("Factory method. For general use.");
  }

  @Test
  void format_inlineCodeWrappedAcrossLines_collapsesToOneSpan() throws IOException {
    final var tree =
        parseMethodDoc(
            "class T {\n /** Types ({@code\n  * int}, {@code long}). */\n void m() {} }");
    assertThat(JavadocMarkdownPrinter.format(tree)).isEqualTo("Types (`int`, `long`).");
  }

  @Test
  void format_codeBlockInPre_keepsLineBreaks() throws IOException {
    final var tree =
        parseMethodDoc(
            "class T {\n /**\n  * <pre>{@code\n  * first();\n  * second();\n  * }</pre>\n  */\n"
                + " void m() {} }");
    final String result = JavadocMarkdownPrinter.format(tree);
    // javac 17 and 21 keep the space after each comment `*` (25 drops it): allow leading
    // whitespace.
    assertThat(result).containsPattern("first\\(\\);\\n\\s*second\\(\\);");
    assertThat(result.lines()).noneMatch(line -> line.strip().equals("`"));
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

  // --- signatureDocs ---

  @Test
  void signatureDocs_description_excludesBlockTagsAndKeepsHtml() throws IOException {
    final var tree =
        parseMethodDoc(
            """
            class T {
              /**
               * Says <b>hello</b>.
               * @param msg the msg
               * @return result
               */
              String m(String msg) { return ""; }
            }
            """);
    assertThat(signatureDocs(tree, "msg").description()).isEqualTo("Says **hello**.");
  }

  @Test
  void signatureDocs_paramDocs_byPositionWithBlanksAndTypeParamsIgnored() throws IOException {
    final var tree =
        parseMethodDoc(
            """
            class T {
              /**
               * Desc.
               * @param <E> type
               * @param count the {@code count} value
               * @param name the recipient
               */
              <E> void m(String name, int count, boolean flag) {}
            }
            """);
    assertThat(signatureDocs(tree, "name", "count", "flag").paramDocs())
        .containsExactly("the recipient", "the `count` value", "");
  }

  @Test
  void signatureDocs_noParamTags_givesBlankParamDocs() throws IOException {
    final var tree =
        parseMethodDoc(
            "class T { /** Just a description. @return x */ int m(int x) { return 0; } }");
    assertThat(signatureDocs(tree, "x").paramDocs()).containsExactly("");
  }

  // --- helpers ---

  private static SignatureDocs signatureDocs(final DocCommentTree tree, final String... names) {
    final List<String> paramNames = List.of(names);
    return JavadocMarkdownPrinter.signatureDocs(
        new DocChain(
            new MethodDoc(paramNames, tree), paramNames, List.of(), method -> Optional.empty()));
  }

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
            JavaSourceCompiler.COMPILER.getTask(
                null, TestJavac.fileManager(List.of(), List.of()), null, null, null, List.of(jfo));
    final CompilationUnitTree cu = task.parse().iterator().next();
    final DocTrees docTrees = DocTrees.instance(task);
    final var classTree = (ClassTree) cu.getTypeDecls().get(0);
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
