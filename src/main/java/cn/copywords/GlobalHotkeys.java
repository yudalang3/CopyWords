package cn.copywords;

import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinDef.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class GlobalHotkeys implements AutoCloseable {
    @FunctionalInterface public interface Commit { void run() throws Exception; }
    private static final int COMMAND = 0x8001;
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final Map<Hotkey, Binding> bindings = new LinkedHashMap<>();
    private final Consumer<PasteRequest> paste;
    private final CapsHotkeys caps;
    private final Thread thread;
    private int threadId, nextId = 1;
    private boolean suspended;
    private volatile boolean closed;

    static final class Command<T> implements Runnable {
        private final Callable<T> action;
        private final AtomicBoolean claimed = new AtomicBoolean();
        final CompletableFuture<T> result = new CompletableFuture<>();

        Command(Callable<T> action) { this.action = action; }

        @Override public void run() {
            if (!claimed.compareAndSet(false, true)) return;
            try { result.complete(action.call()); }
            catch (Throwable exception) { result.completeExceptionally(exception); }
        }

        boolean failBeforeRun(Throwable exception) {
            if (!claimed.compareAndSet(false, true)) return false;
            result.completeExceptionally(exception);
            return true;
        }
    }

    private static final class Binding {
        final int id;
        Entry entry;
        boolean registered;
        Binding(int id, Entry entry) { this.id = id; this.entry = entry; }
    }

    public GlobalHotkeys(Consumer<PasteRequest> paste) {
        this.paste = paste;
        caps = new CapsHotkeys(this::trigger);
        var ready = new CompletableFuture<Void>();
        thread = new Thread(() -> {
            try {
                threadId = Kernel32.INSTANCE.GetCurrentThreadId();
                var message = new WinUser.MSG();
                User32.INSTANCE.PeekMessage(message, null, 0, 0, 0);
                ready.complete(null);
                int result;
                while ((result = User32.INSTANCE.GetMessage(message, null, 0, 0)) > 0) {
                    if (message.message == COMMAND) {
                        Runnable command;
                        while ((command = commands.poll()) != null) command.run();
                    } else if (message.message == WinUser.WM_HOTKEY && !suspended) {
                        int id = message.wParam.intValue();
                        for (var binding : bindings.values()) {
                            if (binding.registered && binding.id == id) {
                                Hotkey key = binding.entry.hotkey();
                                if ((key.modifiers() & Hotkey.ALT) == 0 || caps.takeLeftAltPress(key))
                                    trigger(key, binding.entry.text());
                                break;
                            }
                        }
                    }
                }
                if (result == -1) throw new IllegalStateException("Windows 消息循环失败");
            } catch (Throwable exception) {
                ready.completeExceptionally(exception);
                exception.printStackTrace();
            } finally {
                closed = true;
                bindings.values().forEach(this::unregister);
                caps.close();
                Runnable command;
                while ((command = commands.poll()) != null) command.run();
            }
        }, "CopyWords-hotkeys");
        thread.setDaemon(true);
        thread.start();
        try { ready.join(); } catch (RuntimeException exception) { caps.close(); throw exception; }
    }

    private void trigger(Hotkey key, String text) {
        long time = System.nanoTime();
        paste.accept(new PasteRequest(text, key.key(), InputSimulator.target(), time, caps::isKeyDown));
    }

    private <T> T invoke(Callable<T> action) {
        if (closed) throw new IllegalStateException("快捷键服务已关闭");
        var command = new Command<T>(() -> {
            if (closed) throw new IllegalStateException("快捷键服务已关闭");
            return action.call();
        });
        commands.add(command);
        if (User32.INSTANCE.PostThreadMessage(threadId, COMMAND, new WPARAM(0), new LPARAM(0)) == 0) {
            var exception = new IllegalStateException("无法通知快捷键线程，错误 " + Kernel32.INSTANCE.GetLastError());
            // An earlier wakeup may already have claimed this command. Return its actual result.
            if (command.failBeforeRun(exception)) commands.remove(command);
        }
        try { return command.result.join(); }
        catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            throw new IllegalStateException(cause.getMessage(), cause);
        }
    }

    private Binding register(Entry entry) {
        if (entry.hotkey().reservedForPaste()) throw new IllegalArgumentException(Hotkey.PASTE_ADVICE);
        if (nextId > 0xBFFF) nextId = 1;
        while (idUsed(nextId)) { if (++nextId > 0xBFFF) nextId = 1; }
        var binding = new Binding(nextId++, entry);
        if (!registerNative(binding)) {
            int error = Kernel32.INSTANCE.GetLastError();
            System.err.println("RegisterHotKey failed: " + entry.hotkey() + ", Windows error=" + error);
            throw new IllegalArgumentException(registrationAdvice(entry.hotkey(), error));
        }
        binding.registered = true;
        return binding;
    }

    private boolean idUsed(int id) { return bindings.values().stream().anyMatch(binding -> binding.id == id); }
    private boolean registerNative(Binding binding) {
        Hotkey key = binding.entry.hotkey();
        if (key.reservedForPaste()) return false;
        return key.usesCaps() || User32.INSTANCE.RegisterHotKey(null, binding.id, key.modifiers() | 0x4000, key.key());
    }
    static String registrationAdvice(Hotkey key, int error) {
        String alternative = new Hotkey(Hotkey.CAPS, key.key()).toString();
        String reason = error == 1409 ? "“" + key + "”已被其他程序占用，暂时无法使用。" : "Windows 无法启用“" + key + "”。";
        return reason + "\n请点击这一行的快捷键按钮，改为“" + alternative + "”（推荐），确认后会自动保存并生效。"
                + "\n如需保留原快捷键，请先在其他快捷键软件中关闭相同组合，再回到 CopyWords 点击“重试”。";
    }
    private void publishCaps() {
        var entries = new LinkedHashMap<Hotkey, String>();
        for (var binding : bindings.values())
            if (binding.registered && (binding.entry.hotkey().usesCaps()
                    || (binding.entry.hotkey().modifiers() & Hotkey.ALT) != 0))
                entries.put(binding.entry.hotkey(), binding.entry.text());
        caps.update(entries);
    }
    public void capture(Consumer<Hotkey> listener) { caps.capture(listener); }
    public boolean rightAltDown() { return caps.isKeyDown(0xA5); }
    private void unregister(Binding binding) {
        if (binding.registered && !binding.entry.hotkey().usesCaps()) User32.INSTANCE.UnregisterHotKey(null, binding.id);
        binding.registered = false;
    }

    public List<String> start(Config config) {
        return invoke(() -> {
            var warnings = new ArrayList<String>();
            for (int i = 0; i < config.entries().size(); i++) {
                var entry = config.entries().get(i);
                if (!entry.active()) continue;
                try { bindings.put(entry.hotkey(), register(entry)); }
                catch (IllegalArgumentException exception) { warnings.add("第 " + (i + 1) + " 行：" + exception.getMessage()); }
            }
            publishCaps();
            return warnings;
        });
    }

    // Existing registrations remain live until every new registration and persistence step succeeds.
    public void apply(Config config, Commit commit) {
        config.validate();
        invoke(() -> {
            var staged = new LinkedHashMap<Hotkey, Binding>();
            var desired = new LinkedHashMap<Hotkey, Binding>();
            try {
                for (int i = 0; i < config.entries().size(); i++) {
                    var entry = config.entries().get(i);
                    if (!entry.active()) continue;
                    var existing = bindings.get(entry.hotkey());
                    if (existing != null && existing.registered) desired.put(entry.hotkey(), existing);
                    else {
                        try {
                            var added = register(entry);
                            staged.put(entry.hotkey(), added);
                            desired.put(entry.hotkey(), added);
                        } catch (IllegalArgumentException exception) {
                            throw new IllegalArgumentException("第 " + (i + 1) + " 行：" + exception.getMessage());
                        }
                    }
                }
                commit.run();
            } catch (Exception exception) {
                staged.values().forEach(this::unregister);
                throw exception;
            }
            for (var old : bindings.entrySet()) if (!desired.containsKey(old.getKey())) unregister(old.getValue());
            for (var entry : config.entries()) if (entry.active()) desired.get(entry.hotkey()).entry = entry;
            bindings.clear();
            bindings.putAll(desired);
            publishCaps();
            return null;
        });
    }

    public void pause() {
        invoke(() -> { suspended = true; caps.pause(true); bindings.values().forEach(this::unregister); return null; });
    }

    public List<String> resume() {
        return invoke(() -> {
            var warnings = new ArrayList<String>();
            for (var binding : bindings.values()) {
                var hotkey = binding.entry.hotkey();
                binding.registered = registerNative(binding);
                if (!binding.registered) warnings.add(registrationAdvice(hotkey, Kernel32.INSTANCE.GetLastError()));
            }
            suspended = false;
            publishCaps(); caps.pause(false);
            return warnings;
        });
    }

    @Override public void close() {
        if (closed) return;
        invoke(() -> {
            bindings.values().forEach(this::unregister);
            User32.INSTANCE.PostQuitMessage(0);
            return null;
        });
        try { thread.join(3000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
