/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.aglibs.lathe.format.gjf.java;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.Math.max;

import com.google.common.base.CharMatcher;
import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeMap;
import com.google.common.collect.TreeRangeSet;
import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.ReferenceTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.DocTreePath;
import com.sun.source.util.DocTreePathScanner;
import com.sun.source.util.DocTrees;
import com.sun.source.util.TreePathScanner;
import io.github.aglibs.lathe.format.JavaLexer;
import io.github.aglibs.lathe.format.JavaLexer.LexToken;
import io.github.aglibs.lathe.format.gjf.Newlines;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Removes unused imports from a source file. Imports that are only used in javadoc are also
 * removed, and the references in javadoc are replaced with fully qualified names.
 */
public class RemoveUnusedImports {

  // Visits an AST, recording all simple names that could refer to imported
  // types and also any javadoc references that could refer to imported
  // types (`@link`, `@see`, `@throws`, etc.)
  //
  // No attempt is made to determine whether simple names occur in contexts
  // where they are type names, so there will be false positives. For example,
  // `List` is not identified as unused import below:
  //
  // ```
  // import java.util.List;
  // class List {}
  // ```
  //
  // This is still reasonably effective in practice because type names differ
  // from other kinds of names in casing convention, and simple name
  // clashes between imported and declared types are rare.
  private static class UnusedImportScanner extends TreePathScanner<Void, Void> {

    private final Set<String> usedNames = new LinkedHashSet<>();
    private final Set<String> usedInJavadoc = new LinkedHashSet<>();
    final DocTrees trees;
    final DocTreeScanner docTreeSymbolScanner;

    private UnusedImportScanner(DocTrees trees) {
      this.trees = trees;
      docTreeSymbolScanner = new DocTreeScanner();
    }

    /** Skip the imports themselves when checking for usage. */
    @Override
    public Void visitImport(ImportTree importTree, Void usedSymbols) {
      return null;
    }

    @Override
    public Void visitIdentifier(IdentifierTree tree, Void unused) {
      if (tree == null) {
        return null;
      }
      usedNames.add(tree.getName().toString());
      return null;
    }

    // TODO(cushon): remove this override when pattern matching in switch is no longer a preview
    // feature, and TreePathScanner visits CaseTree#getLabels instead of CaseTree#getExpressions
    @SuppressWarnings("unchecked") // reflection
    @Override
    public Void visitCase(CaseTree tree, Void unused) {
      if (CASE_TREE_GET_LABELS != null) {
        try {
          scan((List<? extends Tree>) CASE_TREE_GET_LABELS.invoke(tree), null);
        } catch (ReflectiveOperationException e) {
          throw new LinkageError(e.getMessage(), e);
        }
      }
      return super.visitCase(tree, null);
    }

    private static final Method CASE_TREE_GET_LABELS = caseTreeGetLabels();

    private static Method caseTreeGetLabels() {
      try {
        return CaseTree.class.getMethod("getLabels");
      } catch (NoSuchMethodException e) {
        return null;
      }
    }

    @Override
    public Void scan(Tree tree, Void unused) {
      if (tree == null) {
        return null;
      }
      scanJavadoc();
      return super.scan(tree, unused);
    }

    private void scanJavadoc() {
      if (getCurrentPath() == null) {
        return;
      }
      DocCommentTree commentTree = trees.getDocCommentTree(getCurrentPath());
      if (commentTree == null) {
        return;
      }
      docTreeSymbolScanner.scan(new DocTreePath(getCurrentPath(), commentTree), null);
    }

    // scan javadoc comments, checking for references to imported types
    class DocTreeScanner extends DocTreePathScanner<Void, Void> {
      @Override
      public Void visitIdentifier(com.sun.source.doctree.IdentifierTree node, Void aVoid) {
        return null;
      }

      @Override
      public Void visitReference(ReferenceTree referenceTree, Void unused) {
        addReferencedNames(referenceTree.getSignature(), usedInJavadoc);
        return null;
      }
    }
  }

  // The public ReferenceTree exposes a javadoc reference only as its signature text, e.g.
  // `java.base/java.util.Map.Entry#put(Object, List<K>)`. Like javac's own reference tree walk,
  // record the identifiers that start a (possibly qualified) name in the qualifier and in the
  // parameter types, but not the module prefix or the member name.
  private static void addReferencedNames(String signature, Set<String> names) {
    String reference = signature.substring(signature.indexOf('/') + 1);
    int memberStart = reference.indexOf('#');
    int paramsStart = reference.indexOf('(');
    int qualifierEnd =
        memberStart >= 0 ? memberStart : paramsStart >= 0 ? paramsStart : reference.length();
    addLeadingIdentifiers(reference.substring(0, qualifierEnd), names);
    if (paramsStart >= 0) {
      addLeadingIdentifiers(reference.substring(paramsStart), names);
    }
  }

  private static void addLeadingIdentifiers(String text, Set<String> names) {
    String previous = "";
    for (LexToken token : JavaLexer.tokenize(text).orElse(List.of())) {
      String tokenText = text.substring(token.start(), token.end());
      if (tokenText.isBlank()) {
        continue;
      }
      if (Character.isJavaIdentifierStart(tokenText.codePointAt(0)) && !previous.equals(".")) {
        names.add(tokenText);
      }
      previous = tokenText;
    }
  }

  public static String removeUnusedImports(final String contents) throws FormatterException {
    Trees.ParsedUnit parsed = Trees.parse(contents, /* allowStringFolding= */ false);
    UnusedImportScanner scanner = new UnusedImportScanner(parsed.trees());
    scanner.scan(parsed.unit(), null);
    return applyReplacements(
        contents, buildReplacements(contents, parsed, scanner.usedNames, scanner.usedInJavadoc));
  }

  /** Construct replacements to fix unused imports. */
  private static RangeMap<Integer, String> buildReplacements(
      String contents, Trees.ParsedUnit parsed, Set<String> usedNames, Set<String> usedInJavadoc) {
    RangeMap<Integer, String> replacements = TreeRangeMap.create();
    for (ImportTree importTree : parsed.unit().getImports()) {
      if (isModuleImport(importTree)) {
        continue;
      }
      String simpleName = getSimpleName(importTree);
      if (!isUnused(parsed.unit(), usedNames, usedInJavadoc, importTree, simpleName)) {
        continue;
      }
      // delete the import
      int endPosition = parsed.getEndPosition(importTree);
      endPosition = max(CharMatcher.isNot(' ').indexIn(contents, endPosition), endPosition);
      String sep = Newlines.guessLineSeparator(contents);
      if (endPosition + sep.length() < contents.length()
          && contents.subSequence(endPosition, endPosition + sep.length()).toString().equals(sep)) {
        endPosition += sep.length();
      }
      replacements.put(Range.closedOpen(parsed.getStartPosition(importTree), endPosition), "");
    }
    return replacements;
  }

  private static String getSimpleName(ImportTree importTree) {
    return getQualifiedIdentifier(importTree).getIdentifier().toString();
  }

  private static boolean isUnused(
      CompilationUnitTree unit,
      Set<String> usedNames,
      Set<String> usedInJavadoc,
      ImportTree importTree,
      String simpleName) {
    MemberSelectTree qualifiedIdentifier = getQualifiedIdentifier(importTree);
    String qualifier = qualifiedIdentifier.getExpression().toString();
    if (qualifier.equals("java.lang")) {
      return true;
    }
    if (unit.getPackageName() != null && unit.getPackageName().toString().equals(qualifier)) {
      return true;
    }
    if (qualifiedIdentifier.getIdentifier().contentEquals("*")) {
      return false;
    }

    if (usedNames.contains(simpleName)) {
      return false;
    }
    if (usedInJavadoc.contains(simpleName)) {
      return false;
    }
    return true;
  }

  private static MemberSelectTree getQualifiedIdentifier(ImportTree importTree) {
    checkArgument(!isModuleImport(importTree));
    return (MemberSelectTree) importTree.getQualifiedIdentifier();
  }

  private static final @Nullable Method IS_MODULE_METHOD = getIsModuleMethod();

  private static @Nullable Method getIsModuleMethod() {
    try {
      return ImportTree.class.getMethod("isModule");
    } catch (NoSuchMethodException ignored) {
      return null;
    }
  }

  private static boolean isModuleImport(ImportTree importTree) {
    if (IS_MODULE_METHOD == null) {
      return false;
    }
    try {
      return (boolean) IS_MODULE_METHOD.invoke(importTree);
    } catch (ReflectiveOperationException e) {
      throw new LinkageError(e.getMessage(), e);
    }
  }

  /** Applies the replacements to the given source, and re-format any edited javadoc. */
  private static String applyReplacements(String source, RangeMap<Integer, String> replacements) {
    // save non-empty fixed ranges for reformatting after fixes are applied
    RangeSet<Integer> fixedRanges = TreeRangeSet.create();

    // Apply the fixes in increasing order, adjusting ranges to account for
    // earlier fixes that change the length of the source. The output ranges are
    // needed so we can reformat fixed regions, otherwise the fixes could just
    // be applied in descending order without adjusting offsets.
    StringBuilder sb = new StringBuilder(source);
    int offset = 0;
    for (Map.Entry<Range<Integer>, String> replacement : replacements.asMapOfRanges().entrySet()) {
      Range<Integer> range = replacement.getKey();
      String replaceWith = replacement.getValue();
      int start = offset + range.lowerEndpoint();
      int end = offset + range.upperEndpoint();
      sb.replace(start, end, replaceWith);
      if (!replaceWith.isEmpty()) {
        fixedRanges.add(Range.closedOpen(start, end));
      }
      offset += replaceWith.length() - (range.upperEndpoint() - range.lowerEndpoint());
    }
    return sb.toString();
  }
}
