package baritone.server;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ServerPathingSchedulerTest {

    @Test
    public void calculationRunsOffCallingThread() throws Exception {
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> worker = new AtomicReference<>();
        Future<?> future = ServerPathingScheduler.submit(
                () -> worker.set(Thread.currentThread()));
        future.get(5, TimeUnit.SECONDS);

        assertTrue(worker.get().getName().startsWith("Baritone-Path-"));
        assertFalse(worker.get() == caller);
        assertTrue(ServerPathingScheduler.workerCount() >= 1);
        assertTrue(ServerPathingScheduler.workerCount() <= 4);
    }

    @Test
    public void noProcessOrCommandPerformsSynchronousAStar() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        List<Path> offenders;
        try (var files = Files.walk(sourceRoot)) {
            offenders = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.endsWith(
                            Path.of("baritone", "Baritone.java")))
                    .filter(path -> {
                        try {
                            return Files.readString(path)
                                    .contains("finder.calculate(");
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    })
                    .toList();
        }
        assertTrue("Synchronous path calculations outside the scheduler: "
                        + offenders, offenders.isEmpty());
    }

    @Test
    public void concurrentCalculationsAreBoundedAndBackpressured()
            throws Exception {
        int workers = ServerPathingScheduler.workerCount();
        CountDownLatch release = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int index = 0; index < workers + 2; index++) {
            futures.add(ServerPathingScheduler.submit(() -> {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(2);
        while (ServerPathingScheduler.activeCount() < workers
                && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(ServerPathingScheduler.activeCount() <= workers);
        assertTrue(ServerPathingScheduler.queuedCount() >= 2);
        release.countDown();
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void miningUsesSharedIndexInsteadOfFullViewScan() throws IOException {
        String source = Files.readString(Path.of(
                "src", "main", "java", "baritone", "process",
                "MineProcess.java"));
        assertFalse(source.contains("scanChunkRadius("));
        assertTrue(source.contains("registerTrackedBlocks("));
    }
}
