package cn.copywords;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ClipboardServiceTest {
    @Test void busyClipboardRetriesThenSucceeds() throws Exception {
        var calls = new AtomicInteger(); var done = new CountDownLatch(1); var value = new AtomicReference<String>();
        try (var clipboard = new ClipboardService(text -> {
            if (calls.incrementAndGet() <= 2) throw new IllegalStateException("busy");
            value.set(text);
        }, status -> done.countDown())) {
            clipboard.copy("  中文  ");
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertEquals(3, calls.get()); assertEquals("  中文  ", value.get());
        }
    }

    @Test void persistentBusyClipboardStopsAfterThreeRetries() throws Exception {
        var calls = new AtomicInteger(); var done = new CountDownLatch(1); var message = new AtomicReference<String>();
        try (var clipboard = new ClipboardService(text -> { calls.incrementAndGet(); throw new IllegalStateException(); },
                status -> { message.set(status); done.countDown(); })) {
            clipboard.copy("text");
            assertTrue(done.await(3, TimeUnit.SECONDS)); assertEquals(4, calls.get());
            assertTrue(message.get().startsWith("复制失败"));
        }
    }

    @Test void newerHotkeyPreventsOldRetryFromOverwritingClipboard() throws Exception {
        var started = new CountDownLatch(1); var done = new CountDownLatch(1); var value = new AtomicReference<String>();
        try (var clipboard = new ClipboardService(text -> {
            if (text.equals("old")) { started.countDown(); throw new IllegalStateException(); }
            value.set(text);
        }, status -> done.countDown())) {
            clipboard.copy("old"); assertTrue(started.await(2, TimeUnit.SECONDS));
            clipboard.copy("new"); assertTrue(done.await(2, TimeUnit.SECONDS));
            Thread.sleep(350);
            assertEquals("new", value.get());
        }
    }
}
