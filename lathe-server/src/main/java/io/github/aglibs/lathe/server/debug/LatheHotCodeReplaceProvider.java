package io.github.aglibs.lathe.server.debug;

import com.microsoft.java.debug.core.adapter.HotCodeReplaceEvent;
import com.microsoft.java.debug.core.adapter.IHotCodeReplaceProvider;
import io.reactivex.Observable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A no-op hot-code-replace provider. The adapter subscribes to HCR events while handling {@code
 * initialize}, so one must be registered even though hot code replace is a Phase 4 feature; this
 * stub never redefines classes and never emits an event.
 */
final class LatheHotCodeReplaceProvider implements IHotCodeReplaceProvider {

  @Override
  public void onClassRedefined(final Consumer<List<String>> consumer) {}

  @Override
  public CompletableFuture<List<String>> redefineClasses() {
    return CompletableFuture.completedFuture(List.of());
  }

  @Override
  public Observable<HotCodeReplaceEvent> getEventHub() {
    return Observable.never();
  }
}
