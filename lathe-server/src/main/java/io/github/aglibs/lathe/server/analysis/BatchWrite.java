package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.Set;

// Several sources written in one javac task: the classes it wrote, and the sources it reported
// errors in. javac writes no class at all when any source has an error, so a non-empty
// failedUris means nothing was written.
public record BatchWrite(Set<String> writtenBinaryNames, Set<String> failedUris) {
  public BatchWrite {
    ValidCheck.check()
        .notNull(writtenBinaryNames, "writtenBinaryNames")
        .notNull(failedUris, "failedUris")
        .validate();
    writtenBinaryNames = Set.copyOf(writtenBinaryNames);
    failedUris = Set.copyOf(failedUris);
  }
}
