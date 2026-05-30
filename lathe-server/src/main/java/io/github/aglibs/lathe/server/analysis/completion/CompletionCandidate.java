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
    TypeMirror valueType) {

  CompletionCandidate {
    ValidCheck.check()
        .notBlank(name, "name")
        .notBlank(label, "label")
        .notNull(kind, "kind")
        .nullOrNotBlank(detail, "detail")
        .notBlank(insertText, "insertText")
        .nullOrNotBlank(sortText, "sortText")
        .validate();
  }

  CompletionCandidate withSortText(final String newSortText) {
    return new CompletionCandidate(
        name, label, kind, detail, insertText, snippet, newSortText, valueType);
  }

  CompletionCandidate withValueType(final TypeMirror newValueType) {
    return new CompletionCandidate(
        name, label, kind, detail, insertText, snippet, sortText, newValueType);
  }
}
