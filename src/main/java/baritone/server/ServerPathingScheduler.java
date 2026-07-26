/*
 * Server-side Baritone fork.
 * Derived from Baritone, licensed under LGPL-3.0.
 */
package baritone.server;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Shared bounded path calculation pool.
 *
 * <p>Upstream Baritone runs path calculations away from the game thread. On a
 * dedicated server the pool must additionally be bounded: an unbounded thread
 * per fake player turns several simultaneous searches into CPU starvation and
 * GC pauses. FIFO submission plus one outstanding calculation per Baritone
 * instance provides backpressure and fair service between fake players.</p>
 */
public final class ServerPathingScheduler {
    private static final int PROCESSORS =
            Runtime.getRuntime().availableProcessors();
    private static final int WORKERS = Math.max(1,
            Math.min(4, PROCESSORS - 2));
    private static final int QUEUE_CAPACITY = 64;
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static final LongAdder SUBMITTED = new LongAdder();
    private static final LongAdder COMPLETED = new LongAdder();
    private static final LongAdder TOTAL_NANOS = new LongAdder();
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            WORKERS,
            WORKERS,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            daemonFactory(),
            new ThreadPoolExecutor.AbortPolicy());

    static {
        EXECUTOR.allowCoreThreadTimeOut(true);
    }

    private ServerPathingScheduler() {}

    public static Future<?> submit(Runnable calculation) {
        SUBMITTED.increment();
        return EXECUTOR.submit(() -> {
            long started = System.nanoTime();
            try {
                calculation.run();
            } finally {
                TOTAL_NANOS.add(System.nanoTime() - started);
                COMPLETED.increment();
            }
        });
    }

    public static int activeCount() {
        return EXECUTOR.getActiveCount();
    }

    public static void cancel(Future<?> future) {
        if (future == null) return;
        future.cancel(false);
        if (future instanceof Runnable runnable) {
            EXECUTOR.remove(runnable);
        }
    }

    public static int queuedCount() {
        return EXECUTOR.getQueue().size();
    }

    public static int workerCount() {
        return WORKERS;
    }

    public static long submittedCount() {
        return SUBMITTED.sum();
    }

    public static long completedCount() {
        return COMPLETED.sum();
    }

    public static double averageCalculationMillis() {
        long completed = COMPLETED.sum();
        return completed == 0L ? 0.0D
                : TOTAL_NANOS.sum() / 1_000_000.0D / completed;
    }

    public static void shutdownNow() {
        EXECUTOR.shutdownNow();
    }

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable,
                    "Baritone-Path-" + THREAD_IDS.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY,
                    Thread.NORM_PRIORITY - 1));
            return thread;
        };
    }
}
