package io.github.aglibs.lathe.format;

// Pluggable backend seam: GJF reference now, the forked formatter later, both for the differential.
@FunctionalInterface
interface FormatSubject {
  String format(String source) throws Exception;
}
