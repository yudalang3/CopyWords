package cn.copywords;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.awt.Toolkit;
import java.awt.datatransfer.*;

final class InputSimulator implements ClipboardService.Platform {
    static final long SYNTHETIC_PASTE = 0x43574F53L;
    private static final int[] MODIFIERS = {0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0x5B, 0x5C};

    public interface ClipboardUser32 extends StdCallLibrary {
        int GetClipboardSequenceNumber();
        boolean OpenClipboard(Pointer owner);
        boolean CloseClipboard();
        Pointer GetClipboardData(int format);
    }
    public interface ClipboardKernel32 extends StdCallLibrary {
        Pointer GlobalLock(Pointer handle);
        boolean GlobalUnlock(Pointer handle);
        BaseTSD.SIZE_T GlobalSize(Pointer handle);
    }
    private static final class ClipboardApi {
        static final ClipboardUser32 INSTANCE = Native.load("user32", ClipboardUser32.class);
        static final ClipboardKernel32 KERNEL = Native.load("kernel32", ClipboardKernel32.class);
    }

    static long clipboardVersion() { return Integer.toUnsignedLong(ClipboardApi.INSTANCE.GetClipboardSequenceNumber()); }
    private static long address(HWND window) { return window == null ? 0 : Pointer.nativeValue(window.getPointer()); }

    static PasteRequest.Target target() {
        HWND window = User32.INSTANCE.GetForegroundWindow();
        if (window == null) return new PasteRequest.Target(0, 0, 0, 0);
        var process = new IntByReference();
        int thread = User32.INSTANCE.GetWindowThreadProcessId(window, process);
        var info = new WinUser.GUITHREADINFO();
        info.cbSize = info.size();
        if (thread == 0 || !User32.INSTANCE.GetGUIThreadInfo(thread, info)
                || address(window) != address(User32.INSTANCE.GetForegroundWindow()))
            return new PasteRequest.Target(0, 0, 0, 0);
        return new PasteRequest.Target(address(window), address(info.hwndFocus), thread, process.getValue());
    }

    @Override public boolean targetMatches(PasteRequest request) { return request.target().equals(target()); }

    @Override public boolean keysReleased(PasteRequest request) {
        for (int key : MODIFIERS)
            if (request.heldKeys().test(key) || User32.INSTANCE.GetAsyncKeyState(key) < 0) return false;
        return !request.heldKeys().test(request.triggerKey()) && !request.heldKeys().test('V')
                && User32.INSTANCE.GetAsyncKeyState('V') >= 0;
    }

    static ClipboardService.ClipboardAccess clipboard() {
        return new ClipboardService.ClipboardAccess() {
            @Override public void write(String text) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            }
            @Override public ClipboardService.Snapshot snapshot(String expectedText) {
                if (!ClipboardApi.INSTANCE.OpenClipboard(null)) throw new IllegalStateException("剪贴板正在被占用");
                try {
                    // Read the native text, rather than AWT's cached Transferable. GetClipboardData
                    // also renders delayed CF_UNICODETEXT before we record its sequence number.
                    Pointer handle = ClipboardApi.INSTANCE.GetClipboardData(13);
                    String text = null;
                    if (handle != null) {
                        Pointer data = ClipboardApi.KERNEL.GlobalLock(handle);
                        if (data == null) throw new IllegalStateException("无法读取剪贴板文本");
                        try {
                            // Clipboard data may have changed externally: bound reads by both the
                            // native allocation and expected text length, including its terminator.
                            long count = Math.min(ClipboardApi.KERNEL.GlobalSize(handle).longValue() / 2,
                                    (long) expectedText.length() + 1);
                            char[] chars = data.getCharArray(0, (int) count);
                            for (int i = 0; i < chars.length; i++) {
                                if (chars[i] == 0) { text = new String(chars, 0, i); break; }
                            }
                        }
                        finally { ClipboardApi.KERNEL.GlobalUnlock(handle); }
                    }
                    long version = clipboardVersion();
                    if (version == 0) throw new IllegalStateException("无法读取剪贴板版本");
                    return new ClipboardService.Snapshot(text, version);
                } finally { ClipboardApi.INSTANCE.CloseClipboard(); }
            }
        };
    }

    @Override public ClipboardService.SendResult send(PasteRequest request, ClipboardService.Snapshot snapshot) {
        // Recheck immediately before injection; never release the user's modifiers to force a paste.
        var inputs = inputs(new int[] {0xA2, 'V', 'V', 0xA2}, new boolean[] {false, false, true, true});
        if (!targetMatches(request) || !keysReleased(request) || clipboardVersion() != snapshot.version())
            throw new IllegalStateException("发送前目标、按键或剪贴板已变化");
        int sent = User32.INSTANCE.SendInput(new DWORD(inputs.length), inputs, inputs[0].size()).intValue();
        int error = sent == inputs.length ? 0 : Kernel32.INSTANCE.GetLastError();
        if (sent > 0 && sent < inputs.length) {
            var keys = new java.util.ArrayList<Integer>();
            if (sent == 2 && !request.heldKeys().test('V')) keys.add((int) 'V');
            if (!request.heldKeys().test(0xA2) && !request.heldKeys().test(0x11)) keys.add(0xA2);
            if (!keys.isEmpty()) {
                int[] releaseKeys = keys.stream().mapToInt(Integer::intValue).toArray();
                boolean[] up = new boolean[releaseKeys.length];
                java.util.Arrays.fill(up, true);
                var releases = inputs(releaseKeys, up);
                int cleaned = User32.INSTANCE.SendInput(new DWORD(releases.length), releases, releases[0].size()).intValue();
                if (cleaned != releases.length) System.err.println("自动粘贴按键清理失败：" + cleaned + "/" + releases.length);
            }
        }
        return new ClipboardService.SendResult(sent, error);
    }

    private static WinUser.INPUT[] inputs(int[] keys, boolean[] up) {
        // JNA requires contiguous native memory, not an array of separately allocated INPUT objects.
        var inputs = (WinUser.INPUT[]) new WinUser.INPUT().toArray(keys.length);
        var layout = User32.INSTANCE.GetKeyboardLayout(0);
        for (int i = 0; i < inputs.length; i++) {
            inputs[i].type = new DWORD(WinUser.INPUT.INPUT_KEYBOARD);
            inputs[i].input.setType("ki");
            inputs[i].input.ki = new WinUser.KEYBDINPUT();
            inputs[i].input.ki.wVk = new WORD(keys[i]);
            inputs[i].input.ki.wScan = new WORD(User32.INSTANCE.MapVirtualKeyEx(keys[i], 4, layout) & 0xFF);
            inputs[i].input.ki.dwFlags = new DWORD(up[i] ? WinUser.KEYBDINPUT.KEYEVENTF_KEYUP : 0);
            inputs[i].input.ki.time = new DWORD(0);
            inputs[i].input.ki.dwExtraInfo = new BaseTSD.ULONG_PTR(SYNTHETIC_PASTE);
        }
        return inputs;
    }
}
