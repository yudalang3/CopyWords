package cn.copywords;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class ClipboardService implements AutoCloseable {
    @FunctionalInterface public interface Writer { void write(String text); }
    private final Writer writer;
    private final Consumer<String> status;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "CopyWords-clipboard");
        thread.setDaemon(true);
        return thread;
    });
    private long generation;

    public ClipboardService(Consumer<String> status) {
        this(text -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null), status);
    }
    public ClipboardService(Writer writer, Consumer<String> status) { this.writer = writer; this.status = status; }

    public void copy(String text) { worker.execute(() -> attempt(text, 0, ++generation)); }

    private void attempt(String text, int retries, long request) {
        if (request != generation) return;
        try { writer.write(text); status.accept("已复制到剪贴板。松开快捷键后，在 Word 或网页按 Ctrl + V 粘贴。"); }
        catch (IllegalStateException exception) {
            if (retries < 3) worker.schedule(() -> attempt(text, retries + 1, request), 100, TimeUnit.MILLISECONDS);
            else status.accept("复制失败：剪贴板正在被其他程序占用，请重新按快捷键");
        } catch (RuntimeException exception) { status.accept("复制失败：" + exception.getMessage()); }
    }

    @Override public void close() { worker.shutdownNow(); }
}
