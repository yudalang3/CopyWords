package cn.copywords;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GlobalHotkeysCommandTest {
    @Test void notificationFailureCancelsCommandAlreadyRemovedFromQueue() throws Exception {
        var executions = new AtomicInteger();
        var command = new GlobalHotkeys.Command<>(executions::incrementAndGet);
        var queue = new ConcurrentLinkedQueue<Runnable>();
        queue.add(command);
        var polled = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failure = new IllegalStateException("notification failed");
        try (var worker = Executors.newSingleThreadExecutor()) {
            var task = worker.submit(() -> {
                Runnable pending = queue.poll();
                polled.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
                pending.run();
                return null;
            });
            try {
                assertTrue(polled.await(3, TimeUnit.SECONDS));
                assertTrue(command.failBeforeRun(failure));
                assertFalse(queue.remove(command));
            } finally { release.countDown(); }
            task.get(3, TimeUnit.SECONDS);
        }
        command.run();
        assertEquals(0, executions.get());
        assertSame(failure, assertThrows(CompletionException.class, command.result::join).getCause());
    }

    @Test void executingCommandKeepsActualResultWhenNotificationFails() throws Exception {
        var executions = new AtomicInteger();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var command = new GlobalHotkeys.Command<>(() -> {
            executions.incrementAndGet();
            started.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return "saved";
        });
        try (var worker = Executors.newSingleThreadExecutor()) {
            var task = worker.submit(command);
            try {
                assertTrue(started.await(3, TimeUnit.SECONDS));
                assertFalse(command.failBeforeRun(new IllegalStateException("notification failed")));
                assertFalse(command.result.isDone());
                command.run();
            } finally { release.countDown(); }
            task.get(3, TimeUnit.SECONDS);
        }
        assertEquals("saved", command.result.join());
        assertFalse(command.failBeforeRun(new IllegalStateException("late notification failure")));
        command.run();
        assertEquals(1, executions.get());
    }

    @Test void executingCommandPreservesActualFailure() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failure = new IOException("disk failure");
        var command = new GlobalHotkeys.Command<>(() -> {
            started.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            throw failure;
        });
        try (var worker = Executors.newSingleThreadExecutor()) {
            var task = worker.submit(command);
            try {
                assertTrue(started.await(3, TimeUnit.SECONDS));
                assertFalse(command.failBeforeRun(new IllegalStateException("notification failed")));
            } finally { release.countDown(); }
            task.get(3, TimeUnit.SECONDS);
        }
        assertSame(failure, assertThrows(CompletionException.class, command.result::join).getCause());
    }
}
