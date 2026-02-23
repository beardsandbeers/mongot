package com.xgen.mongot.metrics;

import com.xgen.mongot.util.functionalinterfaces.CheckedSupplier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Test;

public class TimerTest {
  @Test
  public void testSupplier() throws Exception {
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    Timer timer = meterRegistry.timer("testSupplier");

    CheckedSupplier<Void, Exception> supplier =
        () -> {
          Thread.sleep(200);
          return null;
        };

    Timed.supplier(timer, supplier);
    Timed.supplier(timer, supplier);
    Duration meanDuration = Duration.ofMillis((long) timer.mean(TimeUnit.MILLISECONDS));

    Assert.assertEquals(2, timer.count());
    Assert.assertEquals(200L, meanDuration.toMillis(), 75);
  }
}
