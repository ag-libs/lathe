/*
 * Copyright 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.github.aglibs.lathe.format.gjf.java;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import java.io.IOError;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import javax.lang.model.element.Name;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/** Utilities for working with {@link Tree}s. */
class Trees {

  /**
   * A compilation unit parsed from {@code source}, with the positions of its trees. Positions come
   * from the public {@link SourcePositions}, which need the unit they were parsed in.
   */
  record ParsedUnit(String source, CompilationUnitTree unit, DocTrees trees) {

    /** Returns the source start position of the node. */
    int getStartPosition(Tree tree) {
      return (int) trees.getSourcePositions().getStartPosition(unit, tree);
    }

    /** Returns the source end position of the node. */
    int getEndPosition(Tree tree) {
      return (int) trees.getSourcePositions().getEndPosition(unit, tree);
    }

    /** Returns the length of the source for the node. */
    int getLength(Tree tree) {
      return getEndPosition(tree) - getStartPosition(tree);
    }

    /** Returns the source text for the node. */
    String getSourceForNode(Tree node) {
      return source.substring(getStartPosition(node), getEndPosition(node));
    }
  }

  private static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();

  /** Returns the simple name of a (possibly qualified) method invocation expression. */
  static Name getMethodName(MethodInvocationTree methodInvocation) {
    ExpressionTree select = methodInvocation.getMethodSelect();
    return select instanceof MemberSelectTree
        ? ((MemberSelectTree) select).getIdentifier()
        : ((IdentifierTree) select).getName();
  }

  /** Returns the receiver of a qualified method invocation expression, or {@code null}. */
  static ExpressionTree getMethodReceiver(MethodInvocationTree methodInvocation) {
    ExpressionTree select = methodInvocation.getMethodSelect();
    return select instanceof MemberSelectTree ? ((MemberSelectTree) select).getExpression() : null;
  }

  /** Returns the string name of an operator, including assignment and compound assignment. */
  static String operatorName(ExpressionTree expression) {
    return switch (expression.getKind()) {
      case ASSIGNMENT -> "=";
      case UNARY_PLUS, PLUS -> "+";
      case UNARY_MINUS, MINUS -> "-";
      case LOGICAL_COMPLEMENT -> "!";
      case BITWISE_COMPLEMENT -> "~";
      case PREFIX_INCREMENT, POSTFIX_INCREMENT -> "++";
      case PREFIX_DECREMENT, POSTFIX_DECREMENT -> "--";
      case CONDITIONAL_OR -> "||";
      case CONDITIONAL_AND -> "&&";
      case EQUAL_TO -> "==";
      case NOT_EQUAL_TO -> "!=";
      case LESS_THAN -> "<";
      case GREATER_THAN -> ">";
      case LESS_THAN_EQUAL -> "<=";
      case GREATER_THAN_EQUAL -> ">=";
      case OR -> "|";
      case XOR -> "^";
      case AND -> "&";
      case LEFT_SHIFT -> "<<";
      case RIGHT_SHIFT -> ">>";
      case UNSIGNED_RIGHT_SHIFT -> ">>>";
      case MULTIPLY -> "*";
      case DIVIDE -> "/";
      case REMAINDER -> "%";
      case OR_ASSIGNMENT -> "|=";
      case XOR_ASSIGNMENT -> "^=";
      case AND_ASSIGNMENT -> "&=";
      case LEFT_SHIFT_ASSIGNMENT -> "<<=";
      case RIGHT_SHIFT_ASSIGNMENT -> ">>=";
      case UNSIGNED_RIGHT_SHIFT_ASSIGNMENT -> ">>>=";
      case PLUS_ASSIGNMENT -> "+=";
      case MINUS_ASSIGNMENT -> "-=";
      case MULTIPLY_ASSIGNMENT -> "*=";
      case DIVIDE_ASSIGNMENT -> "/=";
      case REMAINDER_ASSIGNMENT -> "%=";
      default -> throw new AssertionError(expression.getKind());
    };
  }

  /** Returns the precedence of an expression's operator; the values are javac's. */
  static int precedence(ExpressionTree expression) {
    return switch (expression.getKind()) {
      case ASSIGNMENT -> 1;
      case OR_ASSIGNMENT,
          XOR_ASSIGNMENT,
          AND_ASSIGNMENT,
          LEFT_SHIFT_ASSIGNMENT,
          RIGHT_SHIFT_ASSIGNMENT,
          UNSIGNED_RIGHT_SHIFT_ASSIGNMENT,
          PLUS_ASSIGNMENT,
          MINUS_ASSIGNMENT,
          MULTIPLY_ASSIGNMENT,
          DIVIDE_ASSIGNMENT,
          REMAINDER_ASSIGNMENT ->
          2;
      case CONDITIONAL_OR -> 4;
      case CONDITIONAL_AND -> 5;
      case OR -> 6;
      case XOR -> 7;
      case AND -> 8;
      case EQUAL_TO, NOT_EQUAL_TO -> 9;
      case LESS_THAN, GREATER_THAN, LESS_THAN_EQUAL, GREATER_THAN_EQUAL, INSTANCE_OF -> 10;
      case LEFT_SHIFT, RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT -> 11;
      case PLUS, MINUS -> 12;
      case MULTIPLY, DIVIDE, REMAINDER -> 13;
      case UNARY_PLUS,
          UNARY_MINUS,
          LOGICAL_COMPLEMENT,
          BITWISE_COMPLEMENT,
          PREFIX_INCREMENT,
          PREFIX_DECREMENT ->
          14;
      case POSTFIX_INCREMENT, POSTFIX_DECREMENT -> 15;
      default -> throw new AssertionError(expression.getKind());
    };
  }

  /**
   * Returns the enclosing type declaration (class, enum, interface, or annotation) for the given
   * path.
   */
  static ClassTree getEnclosingTypeDeclaration(TreePath path) {
    for (; path != null; path = path.getParentPath()) {
      switch (path.getLeaf().getKind()) {
        case CLASS, ENUM, INTERFACE, ANNOTATED_TYPE -> {
          return (ClassTree) path.getLeaf();
        }
        default -> {}
      }
    }
    throw new AssertionError();
  }

  /** Skips a single parenthesized tree. */
  static ExpressionTree skipParen(ExpressionTree node) {
    return ((ParenthesizedTree) node).getExpression();
  }

  static ParsedUnit parse(
      List<Diagnostic<? extends JavaFileObject>> errorDiagnostics,
      boolean allowStringFolding,
      String javaInput) {
    DiagnosticListener<JavaFileObject> diagnostics =
        diagnostic -> {
          if (errorDiagnostic(diagnostic)) {
            errorDiagnostics.add(diagnostic);
          }
        };
    SimpleJavaFileObject source =
        new SimpleJavaFileObject(URI.create("source"), JavaFileObject.Kind.SOURCE) {
          @Override
          public String getCharContent(boolean ignoreEncodingErrors) {
            return javaInput;
          }
        };
    // Preview syntax is always accepted; it needs -source pinned to the running JDK's version.
    ImmutableList<String> options =
        ImmutableList.of(
            "-proc:none",
            "--enable-preview",
            "-source",
            Integer.toString(Runtime.version().feature()),
            "-XDallowStringFolding=" + allowStringFolding);
    JavacTask task =
        (JavacTask)
            COMPILER.getTask(null, null, diagnostics, options, null, ImmutableList.of(source));
    CompilationUnitTree unit;
    try {
      unit = Iterables.getOnlyElement(task.parse());
    } catch (IOException e) {
      // impossible: the source is in memory
      throw new IOError(e);
    }
    return new ParsedUnit(javaInput, unit, DocTrees.instance(task));
  }

  private static boolean errorDiagnostic(Diagnostic<?> input) {
    if (input.getKind() != Diagnostic.Kind.ERROR) {
      return false;
    }
    // accept constructor-like method declarations that don't match the name of their
    // enclosing class
    return !input.getCode().equals("compiler.err.invalid.meth.decl.ret.type.req");
  }
}
