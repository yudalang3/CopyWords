package cn.copywords;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class ClipboardService implements AutoCloseable {
    interface ClipboardAccess { void write(String text); Snapshot snapshot(String expectedText); }
    interface Platform {
        boolean targetMatches(PasteRequest request);
        boolean keysReleased(PasteRequest request);
        SendResult send(PasteRequest request, Snapshot snapshot);
    }
    interface Scheduler extends AutoCloseable {
        long now();
        void schedule(Runnable task, long delayMillis);
        void close();
    }
    record Snapshot(String text, long version) { }
    record SendResult(int count, int error) { }
    private enum Stage { WAIT_KEYS, WRITE, VERIFY, SEND, GAP }
    private static final long TIMEOUT = TimeUnit.SECONDS.toNanos(1);
    private final ClipboardAccess clipboard;
    private final Platform platform;
    private final Consumer<String> status;
    private final Scheduler worker;
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private final AtomicLong cancellation = new AtomicLong();
    private final Object sending = new Object();
    private volatile boolean accepting = true, closed;
    private Job active;

    private static final class Job {
        final PasteRequest request;
        final long cancellation;
        Stage stage = Stage.WAIT_KEYS;
        Snapshot snapshot;
        int retries;
        Job(PasteRequest request, long cancellation) { this.request = request; this.cancellation = cancellation; }
    }

    private static final class ExecutorScheduler implements Scheduler {
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "CopyWords-clipboard");
            thread.setDaemon(true);
            return thread;
        });
        public long now() { return System.nanoTime(); }
        public void schedule(Runnable task, long delayMillis) { executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS); }
        public void close() { executor.shutdownNow(); }
    }

    public ClipboardService(Consumer<String> status) {
        this(InputSimulator.clipboard(), new InputSimulator(), status, new ExecutorScheduler());
    }
    ClipboardService(ClipboardAccess clipboard, Platform platform, Consumer<String> status) {
        this(clipboard, platform, status, new ExecutorScheduler());
    }
    ClipboardService(ClipboardAccess clipboard, Platform platform, Consumer<String> status, Scheduler worker) {
        this.clipboard = clipboard; this.platform = platform; this.status = status; this.worker = worker;
    }

    public void paste(PasteRequest request) {
        long token = cancellation.get();
        if (!accepting || closed) return;
        schedule(() -> {
            queue.add(new Job(request, token));
            if (active == null) startNext();
        }, 0);
    }

    private void schedule(Runnable task, long delay) {
        if (closed) return;
        try { worker.schedule(task, delay); }
        catch (RejectedExecutionException exception) { if (!closed) throw exception; }
    }

    private void startNext() {
        if (closed || active != null) return;
        active = queue.poll();
        if (active != null) advance(active);
    }

    private boolean valid(Job job) { return accepting && !closed && job.cancellation == cancellation.get(); }
    private void later(Job job, long delay) { schedule(() -> advance(job), delay); }
    private boolean matches(Job job, Snapshot snapshot) {
        return Objects.equals(job.request.text(), snapshot.text())
                && (job.snapshot == null || job.snapshot.version() == snapshot.version());
    }

    private void advance(Job job) {
        if (active != job || closed) return;
        try {
            if (!valid(job)) {
                finish(job.stage == Stage.GAP ? null : "自动粘贴未完成：请求已取消，请重新按快捷键。"); return;
            }
            if (job.stage == Stage.GAP) {
                if (!queue.isEmpty() && !matches(job, clipboard.snapshot(job.request.text()))) externalChange();
                finish(null); return;
            }
            if (worker.now() - job.request.triggeredAt() >= TIMEOUT) {
                finish("自动粘贴未完成：等待超过 1 秒，请松开按键后重新触发。"); return;
            }
            boolean copyOnly = !job.request.target().known();
            if (!copyOnly && !platform.targetMatches(job.request)) {
                finish("自动粘贴未完成：目标窗口或焦点已变化，请在目标输入框重新触发。"); return;
            }
            switch (job.stage) {
                case WAIT_KEYS -> {
                    if (!copyOnly && !platform.keysReleased(job.request)) { later(job, 10); return; }
                    job.stage = Stage.WRITE;
                    later(job, 0);
                }
                case WRITE -> {
                    try { clipboard.write(job.request.text()); }
                    catch (IllegalStateException exception) {
                        if (job.retries++ < 3) { later(job, 100); return; }
                        finish("复制失败：剪贴板正在被其他程序占用，请重新按快捷键。"); return;
                    }
                    job.stage = Stage.VERIFY;
                    later(job, 0);
                }
                case VERIFY -> {
                    Snapshot snapshot;
                    try { snapshot = clipboard.snapshot(job.request.text()); }
                    catch (IllegalStateException exception) { later(job, 10); return; }
                    if (!matches(job, snapshot)) { externalChange(); finish(null); return; }
                    job.snapshot = snapshot;
                    if (copyOnly) { finish("已复制到剪贴板，无法确认目标焦点，请手动按 Ctrl + V 粘贴。"); return; }
                    job.stage = Stage.SEND;
                    later(job, 50);
                }
                case SEND -> {
                    if (!platform.keysReleased(job.request)) { later(job, 10); return; }
                    Snapshot snapshot;
                    try { snapshot = clipboard.snapshot(job.request.text()); }
                    catch (IllegalStateException exception) { later(job, 10); return; }
                    if (!matches(job, snapshot)) { externalChange(); finish(null); return; }
                    SendResult result;
                    synchronized (sending) {
                        if (!valid(job)) { finish("自动粘贴未完成：请求已取消，请重新按快捷键。"); return; }
                        result = platform.send(job.request, snapshot);
                    }
                    if (result.count() == 4) status.accept("已复制并发送粘贴命令。");
                    else {
                        System.err.println("自动粘贴输入发送失败：" + result.count() + "/4，Windows error=" + result.error());
                        status.accept("自动粘贴未完成：已复制到剪贴板，但发送失败，请手动粘贴；管理员窗口可能阻止模拟输入。");
                    }
                    job.stage = Stage.GAP;
                    later(job, 100);
                }
                default -> throw new IllegalStateException("未知粘贴阶段");
            }
        } catch (RuntimeException | LinkageError exception) {
            exception.printStackTrace();
            if (job.stage == Stage.GAP) cancellation.incrementAndGet();
            finish("自动粘贴未完成：" + exception.getMessage() + "。请检查剪贴板后手动粘贴，或重新触发。");
        }
    }

    private void externalChange() {
        cancellation.incrementAndGet();
        status.accept("自动粘贴未完成：剪贴板已被其他程序修改，已保留外部内容并取消待发送请求。");
    }

    private void finish(String message) {
        active = null;
        if (message != null) status.accept(message);
        schedule(this::startNext, 0);
    }

    public void cancelPending() {
        synchronized (sending) { cancellation.incrementAndGet(); }
        schedule(() -> {
            queue.removeIf(job -> job.cancellation != cancellation.get());
            if (active != null && !valid(active)) advance(active);
        }, 0);
    }

    public void pause() { accepting = false; cancelPending(); }
    public void resume() { if (!closed) accepting = true; }

    @Override public void close() {
        accepting = false;
        synchronized (sending) { closed = true; cancellation.incrementAndGet(); }
        worker.close();
    }
}
