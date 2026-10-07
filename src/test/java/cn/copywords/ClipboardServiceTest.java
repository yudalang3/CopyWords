package cn.copywords;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ClipboardServiceTest {
    private static String captureErrors(Runnable action) {
        PrintStream previous = System.err;
        var output = new ByteArrayOutputStream();
        try (var capture = new PrintStream(output)) {
            System.setErr(capture);
            try { action.run(); } finally { System.setErr(previous); }
        }
        return output.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
    private static final class Environment implements ClipboardService.ClipboardAccess, ClipboardService.Platform,
            ClipboardService.Scheduler, AutoCloseable {
        record Task(long time, long order, Runnable action) { }
        final PriorityQueue<Task> tasks = new PriorityQueue<>(Comparator.comparingLong(Task::time).thenComparingLong(Task::order));
        final List<String> events = new ArrayList<>(), messages = new ArrayList<>();
        final ClipboardService service = new ClipboardService(this, this, messages::add, this);
        long millis, order, version = 1;
        String text;
        int writes, busyWrites, busyReads, sent = 4;
        boolean released = true, sameTarget = true, throwOnSend;
        public long now() { return TimeUnit.MILLISECONDS.toNanos(millis); }
        public void schedule(Runnable action, long delay) { tasks.add(new Task(millis + delay, order++, action)); }
        public void close() { tasks.clear(); }
        void advance(long duration) {
            long until = millis + duration;
            while (!tasks.isEmpty() && tasks.peek().time() <= until) {
                Task task = tasks.remove(); millis = task.time(); task.action().run();
            }
            millis = until;
        }
        void paste(String value) { service.paste(request(value, true)); }
        PasteRequest request(String value, boolean known) {
            return new PasteRequest(value, '1', new PasteRequest.Target(known ? 1 : 0, 2, known ? 3 : 0, 4), now(), key -> false);
        }
        public void write(String value) {
            writes++;
            if (busyWrites-- > 0) throw new IllegalStateException("busy");
            text = value; version++; events.add("write:" + value);
        }
        public ClipboardService.Snapshot snapshot(String expectedText) {
            if (busyReads-- > 0) throw new IllegalStateException("busy read");
            return new ClipboardService.Snapshot(text, version);
        }
        public boolean targetMatches(PasteRequest request) { return sameTarget; }
        public boolean keysReleased(PasteRequest request) { return released; }
        public ClipboardService.SendResult send(PasteRequest request, ClipboardService.Snapshot snapshot) {
            if (throwOnSend) throw new IllegalArgumentException("sender failure");
            events.add("send:" + text);
            return new ClipboardService.SendResult(sent, sent == 4 ? 0 : 5);
        }
        void externalCopy(String value) { text = value; version++; }
    }

    @Test void busyClipboardRetriesThenSendsExactChineseAndWhitespace() {
        try (var env = new Environment()) {
            env.busyWrites = 2; env.paste("  中文  "); env.advance(400);
            assertEquals(3, env.writes);
            assertEquals(List.of("write:  中文  ", "send:  中文  "), env.events);
            assertTrue(env.messages.getFirst().contains("发送粘贴命令"));
        }
    }

    @Test void persistentBusyClipboardStopsAfterThreeRetriesWithoutSending() {
        try (var env = new Environment()) {
            env.busyWrites = 10; env.paste("text"); env.advance(1000);
            assertEquals(4, env.writes); assertTrue(env.events.isEmpty());
            assertTrue(env.messages.getFirst().startsWith("复制失败"));
        }
    }

    @Test void rapidRequestsKeepWholeWriteAndPasteLifecycleInOrder() {
        try (var env = new Environment()) {
            env.paste("A"); env.advance(20); env.paste("B"); env.advance(129);
            assertEquals(List.of("write:A", "send:A"), env.events);
            env.advance(200);
            assertEquals(List.of("write:A", "send:A", "write:B", "send:B"), env.events);
        }
    }

    @Test void newerRequestDoesNotReplaceOlderClipboardRetry() {
        try (var env = new Environment()) {
            env.busyWrites = 2; env.paste("A"); env.advance(20); env.paste("B"); env.advance(600);
            assertEquals(List.of("write:A", "send:A", "write:B", "send:B"), env.events);
        }
    }

    @Test void heldKeysWaitForReleaseAndDoNotOverwriteClipboard() {
        try (var env = new Environment()) {
            env.released = false; env.paste("A"); env.advance(500);
            assertTrue(env.events.isEmpty());
            env.released = true; env.advance(200);
            assertEquals(List.of("write:A", "send:A"), env.events);
        }
    }

    @Test void heldKeysAndQueuedRequestsExpireWithoutSending() {
        try (var env = new Environment()) {
            env.released = false; env.paste("A"); env.paste("B"); env.advance(1200);
            assertTrue(env.events.isEmpty()); assertEquals(2, env.messages.size());
            assertTrue(env.messages.stream().allMatch(message -> message.contains("超过 1 秒")));
        }
    }

    @Test void changingTargetDuringBufferCancelsPaste() {
        try (var env = new Environment()) {
            env.paste("A"); env.advance(20); env.sameTarget = false; env.advance(200);
            assertEquals(List.of("write:A"), env.events);
            assertTrue(env.messages.getFirst().contains("焦点已变化"));
        }
    }

    @Test void unknownTargetCopiesOnly() {
        try (var env = new Environment()) {
            env.service.paste(env.request("A", false)); env.advance(200);
            assertEquals(List.of("write:A"), env.events);
            assertTrue(env.messages.getFirst().contains("请手动"));
        }
    }

    @Test void externalCopyDuringBufferPreservesExternalContentAndCancelsQueue() {
        try (var env = new Environment()) {
            env.paste("A"); env.paste("B"); env.advance(20); env.externalCopy("outside"); env.advance(400);
            assertEquals("outside", env.text); assertEquals(List.of("write:A"), env.events);
        }
    }

    @Test void externalCopyDuringConsumptionGapPreventsNextWrite() {
        try (var env = new Environment()) {
            env.paste("A"); env.paste("B"); env.advance(70); env.externalCopy("outside"); env.advance(400);
            assertEquals("outside", env.text); assertEquals(List.of("write:A", "send:A"), env.events);
        }
    }

    @Test void pauseAndResumeNeverReplayCancelledRequests() {
        try (var env = new Environment()) {
            env.paste("A"); env.paste("B"); env.advance(20);
            env.service.pause(); env.paste("ignored"); env.service.resume(); env.paste("C"); env.advance(400);
            assertEquals(List.of("write:A", "write:C", "send:C"), env.events);
        }
    }

    @Test void closeDuringBufferPreventsAllLaterInput() {
        var env = new Environment();
        env.paste("A"); env.advance(20); env.service.close(); env.advance(300); env.paste("B"); env.advance(300);
        assertEquals(List.of("write:A"), env.events);
    }

    @Test void zeroOrPartialInputCountsAreNotReportedAsSuccessOrRetried() {
        String errors = captureErrors(() -> { for (int sent : new int[] {0, 2}) {
            try (var env = new Environment()) {
                env.sent = sent; env.paste("A"); env.advance(400);
                assertEquals(List.of("write:A", "send:A"), env.events);
                assertTrue(env.messages.getFirst().contains("发送失败"));
                assertFalse(env.messages.getFirst().contains("发送粘贴命令"));
            }
        } });
        assertTrue(errors.contains("Windows error=5"));
    }

    @Test void asyncSenderExceptionIsReportedAndNextRequestStillRuns() {
        String errors = captureErrors(() -> { try (var env = new Environment()) {
            env.throwOnSend = true; env.paste("A"); env.advance(60);
            assertTrue(env.messages.getFirst().contains("sender failure"));
            env.throwOnSend = false; env.paste("B"); env.advance(300);
            assertEquals(List.of("write:A", "write:B", "send:B"), env.events);
        } });
        assertTrue(errors.contains("sender failure"));
    }

    @Test void busyClipboardReadDoesNotRepeatTheSuccessfulWrite() {
        try (var env = new Environment()) {
            env.busyReads = 3; env.paste("A"); env.advance(400);
            assertEquals(1, env.writes); assertEquals(List.of("write:A", "send:A"), env.events);
        }
    }
}
