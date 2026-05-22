package io.github.aglibs.lathe.server.analysis.completion;

enum SentinelContext {
  MEMBER_ACCESS,
  SIMPLE_NAME,
  TYPE_REFERENCE,
  ARGUMENT_POSITION,
  CONSTRUCTOR_CALL,
  ANNOTATION_CONTEXT,
  LAMBDA_BODY,
  MODULE_DIRECTIVE
}
