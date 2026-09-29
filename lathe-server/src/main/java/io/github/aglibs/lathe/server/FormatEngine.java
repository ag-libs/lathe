package io.github.aglibs.lathe.server;

// A Java source-to-source formatter. Throws its native failure type; JavaFormatter catches late.
sealed interface FormatEngine permits GoogleFormatEngine, ExternalCommandFormatEngine {

  String format(String source) throws Exception;
}
