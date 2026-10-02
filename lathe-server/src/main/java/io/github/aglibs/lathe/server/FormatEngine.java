package io.github.aglibs.lathe.server;

import java.nio.file.Path;

// A Java source-to-source formatter. `file` is the document's path, used by engines that format in
// place (FileCommandFormatEngine); the in-process and stdin/stdout engines ignore it. Throws its
// native failure type; JavaFormatter catches late.
sealed interface FormatEngine
    permits GoogleFormatEngine, ExternalCommandFormatEngine, FileCommandFormatEngine {

  String format(String source, Path file) throws Exception;
}
