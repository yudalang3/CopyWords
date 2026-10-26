package cn.copywords;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinDef.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/** A dedicated hook thread: callbacks only match keys and queue copy/capture work. */
final class CapsHotkeys implements AutoCloseable {
    @FunctionalInterface interface Paste { void accept(int triggerKey, String text, int caretFromEnd); }
    private static final long SYNTHETIC_CAPS = 0x43574F52L;
    private final Paste paste;
    private final Set<Integer> downKeys = new HashSet<>(), consumed = new HashSet<>();
    private final WinUser.LowLevelKeyboardProc callback = this::onKey;
    private volatile Map<Hotkey, String> bindings = Map.of();
    private volatile Consumer<Hotkey> capture;
    private volatile boolean paused;
    private volatile Set<Integer> heldKeys = Set.of();
    private record AltPress(long time, boolean leftOnly) { }
    private final Map<Hotkey, ConcurrentLinkedQueue<AltPress>> altPresses = new ConcurrentHashMap<>();
    private final Thread thread;
    private WinUser.HHOOK hook;
    private int threadId;
    private boolean capsHeld, capsUsed;

    CapsHotkeys(Paste paste) {
        this.paste = paste;
        var ready = new CompletableFuture<Void>();
        thread = new Thread(() -> {
            try {
                threadId = Kernel32.INSTANCE.GetCurrentThreadId();
                var message = new WinUser.MSG();
                User32.INSTANCE.PeekMessage(message, null, 0, 0, 0);
                for (int key : new int[] {0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0x5B, 0x5C, 'V'})
                    if (User32.INSTANCE.GetAsyncKeyState(key) < 0) downKeys.add(key);
                heldKeys = Set.copyOf(downKeys);
                hook = User32.INSTANCE.SetWindowsHookEx(WinUser.WH_KEYBOARD_LL, callback, Kernel32.INSTANCE.GetModuleHandle(null), 0);
                if (hook == null) throw new IllegalStateException("无法启用 Caps 快捷键监听。请退出 CopyWords 后重新启动；仍失败时检查安全软件是否阻止了键盘监听。");
                ready.complete(null);
                while (User32.INSTANCE.GetMessage(message, null, 0, 0) > 0) { }
            } catch (Throwable exception) { ready.completeExceptionally(exception); exception.printStackTrace(); }
            finally { if (hook != null) User32.INSTANCE.UnhookWindowsHookEx(hook); }
        }, "CopyWords-Caps-hook");
        thread.setDaemon(true); thread.start(); ready.join();
    }

    void update(Map<Hotkey, String> bindings) { this.bindings = Map.copyOf(bindings); altPresses.clear(); }
    void pause(boolean paused) { this.paused = paused; altPresses.clear(); }
    void capture(Consumer<Hotkey> listener) { capture = listener; }
    boolean isKeyDown(int key) { return heldKeys.contains(key); }
    static boolean trackCaps(boolean capturing, boolean paused) { return capturing || !paused; }
    static boolean fireWritingKey(boolean repeated, boolean capturing, boolean paused) {
        return !repeated && !capturing && !paused;
    }
    boolean takeLeftAltPress(Hotkey key) {
        var presses = altPresses.get(key);
        if (presses == null) return false;
        AltPress press;
        while ((press = presses.poll()) != null)
            if (System.nanoTime() - press.time() < 1_000_000_000L) return press.leftOnly();
        return false;
    }

    private LRESULT next(int code, WPARAM event, WinUser.KBDLLHOOKSTRUCT key) {
        return User32.INSTANCE.CallNextHookEx(hook, code, event, new LPARAM(Pointer.nativeValue(key.getPointer())));
    }

    private LRESULT onKey(int code, WPARAM event, WinUser.KBDLLHOOKSTRUCT key) {
        if (code < 0 || key.dwExtraInfo.longValue() == SYNTHETIC_CAPS
                || key.dwExtraInfo.longValue() == InputSimulator.SYNTHETIC_PASTE) return next(code, event, key);
        try {
            int type = event.intValue(), vk = key.vkCode;
            boolean down = type == WinUser.WM_KEYDOWN || type == WinUser.WM_SYSKEYDOWN;
            boolean wasDown = downKeys.contains(vk);
            if (down) downKeys.add(vk); else downKeys.remove(vk);
            heldKeys = Set.copyOf(downKeys);
            // MOD_ALT merges both sides. Preserve each press until WM_HOTKEY is dispatched,
            // including quick releases; checking only the current key state loses that identity.
            if (!capsHeld && !paused && capture == null && down && !wasDown && Hotkey.supported(vk)
                    && pressed(0xA4, 0xA5, 0x12) && !downKeys.contains(0x5B) && !downKeys.contains(0x5C)) {
                int modifiers = Hotkey.ALT | (pressed(0xA2, 0xA3, 0x11) ? Hotkey.CTRL : 0)
                        | (pressed(0xA0, 0xA1, 0x10) ? Hotkey.SHIFT : 0);
                var hotkey = new Hotkey(modifiers, vk);
                if (bindings.containsKey(hotkey)) {
                    var press = new AltPress(System.nanoTime(), downKeys.contains(0xA4) && !downKeys.contains(0xA5));
                    LRESULT result = next(code, event, key);
                    if (result.longValue() == 0) {
                        var presses = altPresses.computeIfAbsent(hotkey, ignored -> new ConcurrentLinkedQueue<>());
                        presses.removeIf(old -> press.time() - old.time() >= 1_000_000_000L);
                        presses.add(press);
                    }
                    return result;
                }
            }
            if (vk == 0x14) {
                if (down && !wasDown && trackCaps(capture != null, paused)) {
                    capsHeld = true; capsUsed = false;
                }
                if (capsHeld) {
                    if (!down) {
                        capsHeld = false;
                        if (!capsUsed && capture == null) tapCaps();
                    }
                    return new LRESULT(1);
                }
                return next(code, event, key);
            }
            if (consumed.contains(vk)) {
                if (!down) consumed.remove(vk);
                return new LRESULT(1);
            }
            if (capsHeld && down) {
                capsUsed = true;
                if (downKeys.contains(0xA5)) return next(code, event, key);
                if (!downKeys.contains(0x5B) && !downKeys.contains(0x5C)
                        && !pressed(0xA2, 0xA3, 0x11) && !pressed(0xA0, 0xA1, 0x10) && !downKeys.contains(0xA4)) {
                    MarkdownShortcuts.Action writing = MarkdownShortcuts.match(vk);
                    if (writing != null) {
                        if (!fireWritingKey(wasDown, capture != null, paused)) return next(code, event, key);
                        consumed.add(vk);
                        paste.accept(vk, writing.text(), writing.caretFromEnd());
                        return new LRESULT(1);
                    }
                }
                if (Hotkey.supported(vk) && !downKeys.contains(0x5B) && !downKeys.contains(0x5C)) {
                    int modifiers = Hotkey.CAPS
                            | (pressed(0xA2, 0xA3, 0x11) ? Hotkey.CTRL : 0)
                            | (pressed(0xA0, 0xA1, 0x10) ? Hotkey.SHIFT : 0)
                            | (downKeys.contains(0xA4) ? Hotkey.ALT : 0);
                    Hotkey hotkey = new Hotkey(modifiers, vk);
                    Consumer<Hotkey> listener = capture;
                    String text = bindings.get(hotkey);
                    if (listener != null || !paused && text != null) {
                        consumed.add(vk);
                        if (!wasDown) { if (listener != null) listener.accept(hotkey); else paste.accept(vk, text, 0); }
                        return new LRESULT(1);
                    }
                }
            }
        } catch (Throwable exception) { exception.printStackTrace(); }
        return next(code, event, key);
    }

    private boolean pressed(int left, int right, int generic) {
        return downKeys.contains(left) || downKeys.contains(right) || downKeys.contains(generic);
    }

    private void tapCaps() {
        var inputs = (WinUser.INPUT[]) new WinUser.INPUT().toArray(2);
        for (int i = 0; i < 2; i++) {
            inputs[i].type = new DWORD(WinUser.INPUT.INPUT_KEYBOARD);
            inputs[i].input.setType("ki");
            inputs[i].input.ki = new WinUser.KEYBDINPUT();
            inputs[i].input.ki.wVk = new WORD(0x14);
            inputs[i].input.ki.wScan = new WORD(0x3A);
            inputs[i].input.ki.dwFlags = new DWORD(i == 0 ? 0 : WinUser.KEYBDINPUT.KEYEVENTF_KEYUP);
            inputs[i].input.ki.time = new DWORD(0);
            inputs[i].input.ki.dwExtraInfo = new BaseTSD.ULONG_PTR(SYNTHETIC_CAPS);
        }
        if (User32.INSTANCE.SendInput(new DWORD(2), inputs, inputs[0].size()).intValue() != 2)
            System.err.println("Caps 单键切换失败：" + Kernel32.INSTANCE.GetLastError());
    }

    @Override public void close() {
        bindings = Map.of(); capture = null;
        User32.INSTANCE.PostThreadMessage(threadId, WinUser.WM_QUIT, new WPARAM(0), new LPARAM(0));
        try { thread.join(3000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
