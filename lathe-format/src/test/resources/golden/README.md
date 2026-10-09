# Golden fixtures

Copied verbatim from google-java-format `v1.35.0`
(`core/src/test/resources/com/google/googlejavaformat/java/testdata`),
Copyright Google Inc., Apache License 2.0.

Each `<name>.input` formatted by the reference pipeline (`formatSource` + `StringWrapper.wrap`) must
equal `<name>.output`. A handful of fixtures require a newer JDK to parse; `CorpusProvider` gates
them by runtime feature version, mirroring GJF's own `FormatterIntegrationTest`.
