package io.github.aglibs.lathe.server.analysis;

import java.util.List;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

interface CodeActionProvider {

  List<Either<Command, CodeAction>> provide(
      CodeActionRequest request, AttributedFileAnalysis analysis, WorkspaceTypeIndex typeIndex);
}
