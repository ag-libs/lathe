package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryNotificationInfo;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.management.Notification;
import org.junit.jupiter.api.Test;

class MemoryPressureTest {

  // Every heap pool that can report after a collection gets the threshold, whatever the collector.
  // Only that report starts a round, and reports while one is queued join it.
  @Test
  void new_setsTheThresholdOnHeapPools_collectionReportsQueueOneRoundAtATime() {
    final var queued = new ArrayList<Runnable>();
    final var rounds = new AtomicInteger();
    final var pressure = new MemoryPressure(queued::add, rounds::incrementAndGet);
    try {
      final List<MemoryPoolMXBean> pools =
          ManagementFactory.getMemoryPoolMXBeans().stream()
              .filter(pool -> pool.getType() == MemoryType.HEAP)
              .filter(MemoryPoolMXBean::isCollectionUsageThresholdSupported)
              .filter(pool -> pool.getUsage().getMax() > 0)
              .toList();
      assertThat(pools).isNotEmpty();
      assertThat(pools)
          .allMatch(
              pool ->
                  pool.getCollectionUsageThreshold()
                      == (long) (pool.getUsage().getMax() * MemoryPressure.THRESHOLD));

      pressure.onNotification(notification(MemoryNotificationInfo.MEMORY_THRESHOLD_EXCEEDED));
      pressure.onNotification(collectionReport());
      pressure.onNotification(collectionReport());
      assertThat(queued).as("one round queued for two reports").hasSize(1);

      queued.remove(0).run();
      pressure.onNotification(collectionReport());

      assertThat(rounds).hasValue(1);
      assertThat(queued).as("a report after the round ran queues the next").hasSize(1);
    } finally {
      pressure.close();
    }
  }

  private static Notification collectionReport() {
    return notification(MemoryNotificationInfo.MEMORY_COLLECTION_THRESHOLD_EXCEEDED);
  }

  private static Notification notification(final String type) {
    return new Notification(type, "pool", 1);
  }
}
