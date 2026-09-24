package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.validcheck.ValidCheck;
import javax.lang.model.type.TypeMirror;

record CompletionCandidate(
    String name,
    String label,
    CandidateKind kind,
    String detail,
    String insertText,
    boolean snippet,
    String sortText,
    String labelDetail,
    String labelDescription,
    TypeMirror valueType,
    String declaringType,
    ImportEdit importEdit) {

  CompletionCandidate(
      final String name,
      final String label,
      final CandidateKind kind,
      final String detail,
      final String insertText,
      final boolean snippet,
      final String sortText,
      final TypeMirror valueType,
      final String declaringType,
      final ImportEdit importEdit) {
    this(
        name,
        label,
        kind,
        detail,
        insertText,
        snippet,
        sortText,
        null,
        null,
        valueType,
        declaringType,
        importEdit);
  }

  CompletionCandidate {
    ValidCheck.check()
        .notBlank(name, "name")
        .notBlank(label, "label")
        .notNull(kind, "kind")
        .nullOrNotBlank(detail, "detail")
        .notBlank(insertText, "insertText")
        .nullOrNotBlank(sortText, "sortText")
        .nullOrNotBlank(labelDetail, "labelDetail")
        .nullOrNotBlank(labelDescription, "labelDescription")
        .validate();
  }

  CompletionCandidate withSortText(final String newSortText) {
    return new CompletionCandidate(
        name,
        label,
        kind,
        detail,
        insertText,
        snippet,
        newSortText,
        labelDetail,
        labelDescription,
        valueType,
        declaringType,
        importEdit);
  }

  // Recast a member candidate as a `::` reference: it inserts the bare member name (never the
  // method-call snippet with parentheses) and carries a non-callable kind so clients do not
  // auto-append `()`.
  CompletionCandidate asMethodReference() {
    return new CompletionCandidate(
        name,
        label,
        CandidateKind.METHOD_REFERENCE,
        detail,
        name,
        false,
        sortText,
        labelDetail,
        labelDescription,
        valueType,
        declaringType,
        importEdit);
  }
}
