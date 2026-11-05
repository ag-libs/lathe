package io.github.aglibs.lathe.analysis.completion;

import io.github.aglibs.validcheck.ValidCheck;

record RankedCompletionCandidate(
    CompletionCandidate candidate, String sortText, boolean incomplete) {

  RankedCompletionCandidate {
    ValidCheck.check()
        .notNull(candidate, "candidate")
        .nullOrNotBlank(sortText, "sortText")
        .validate();
  }
}
