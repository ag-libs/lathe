package io.github.aglibs.lathe.server;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryNotificationInfo;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;

// The JVM's own report that the heap is still mostly full right after a collection, so only
// releasing something frees memory. It costs nothing until it fires. Every heap pool with a
// maximum gets the threshold, none by name, so it works whatever the collector. Young pools are
// nearly empty after a collection, so in practice the old generation reports.
final class MemoryPressure implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(MemoryPressure.class.getName());
  static final double THRESHOLD = 0.7;

  private final List<MemoryPoolMXBean> pools;
  private final NotificationListener listener;
  private final Executor eventLoop;
  private final Runnable onPressure;
  // The JVM reports after every collection that ends above the threshold, many a second under
  // pressure: one round at a time is queued, and the reports meanwhile are part of it.
  private final AtomicBoolean roundQueued = new AtomicBoolean();

  // onPressure runs on eventLoop.
  MemoryPressure(final Executor eventLoop, final Runnable onPressure) {
    this.eventLoop = eventLoop;
    this.onPressure = onPressure;
    this.pools =
        ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> pool.getType() == MemoryType.HEAP)
            .filter(MemoryPoolMXBean::isCollectionUsageThresholdSupported)
            .filter(pool -> pool.getUsage().getMax() > 0)
            .toList();
    pools.forEach(
        pool -> pool.setCollectionUsageThreshold((long) (pool.getUsage().getMax() * THRESHOLD)));
    this.listener = (notification, handback) -> onNotification(notification);
    emitter().addNotificationListener(listener, null, null);
    LOG.fine(
        () ->
            "[memory] watching %s above %d%% after a collection"
                .formatted(
                    pools.stream().map(MemoryPoolMXBean::getName).toList(),
                    Math.round(THRESHOLD * 100)));
  }

  void onNotification(final Notification notification) {
    if (MemoryNotificationInfo.MEMORY_COLLECTION_THRESHOLD_EXCEEDED.equals(notification.getType())
        && roundQueued.compareAndSet(false, true)) {
      eventLoop.execute(
          () -> {
            roundQueued.set(false);
            onPressure.run();
          });
    }
  }

  // Still above the threshold after the latest collection.
  boolean exceeded() {
    return pools.stream().anyMatch(MemoryPoolMXBean::isCollectionUsageThresholdExceeded);
  }

  // The old generation's use after the latest collection and its maximum, in MB, for the log.
  String usage() {
    return pools.stream()
        .filter(pool -> pool.getCollectionUsage() != null)
        .max(Comparator.comparingLong(pool -> pool.getUsage().getMax()))
        .map(
            pool ->
                "%dMB/%dMB"
                    .formatted(
                        pool.getCollectionUsage().getUsed() >> 20, pool.getUsage().getMax() >> 20))
        .orElse("unknown");
  }

  @Override
  public void close() {
    try {
      emitter().removeNotificationListener(listener);
    } catch (final ListenerNotFoundException e) {
      // The constructor added it; the checked exception cannot happen.
    }
  }

  private static NotificationEmitter emitter() {
    return (NotificationEmitter) ManagementFactory.getMemoryMXBean();
  }
}
