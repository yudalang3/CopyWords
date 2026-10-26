package cn.copywords;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinDef.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "copywords.windowsSmoke", matches = "true")
class WindowsSmokeTest {
    @TempDir Path directory;

    static void key(int key, boolean down) {
        var input = new WinUser.INPUT();
        input.type = new DWORD(WinUser.INPUT.INPUT_KEYBOARD);
        input.input.setType("ki");
        input.input.ki = new WinUser.KEYBDINPUT();
        // Scan-code injection models the physical left/right keys consistently across layouts.
        input.input.ki.wVk = new WORD(0);
        input.input.ki.wScan = new WORD(User32.INSTANCE.MapVirtualKeyEx(key, 4, User32.INSTANCE.GetKeyboardLayout(0)) & 0xFF);
        input.input.ki.dwFlags = new DWORD(WinUser.KEYBDINPUT.KEYEVENTF_SCANCODE
                | (down ? 0 : WinUser.KEYBDINPUT.KEYEVENTF_KEYUP)
                | (key == 0xA5 || key == 0xA3 ? WinUser.KEYBDINPUT.KEYEVENTF_EXTENDEDKEY : 0));
        input.input.ki.time = new DWORD(0);
        input.input.ki.dwExtraInfo = new BaseTSD.ULONG_PTR(0);
        assertEquals(1, User32.INSTANCE.SendInput(new DWORD(1), new WinUser.INPUT[] {input}, input.size()).intValue());
    }

    static void press(int modifier, int main) throws Exception {
        key(modifier, true);
        try { Thread.sleep(80); key(main, true); Thread.sleep(60); key(main, false); }
        finally { key(modifier, false); }
    }

    static String clipboardText() throws Exception {
        return (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
    }

    static void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("condition did not become true within 3 seconds");
            Thread.sleep(10);
        }
    }

    static String editorText(JTextArea editor) {
        var value = new AtomicReference<String>();
        try { SwingUtilities.invokeAndWait(() -> value.set(editor.getText())); }
        catch (Exception exception) { throw new RuntimeException(exception); }
        return value.get();
    }

    static int editorCaret(JTextArea editor) {
        var value = new AtomicInteger();
        try { SwingUtilities.invokeAndWait(() -> value.set(editor.getCaretPosition())); }
        catch (Exception exception) { throw new RuntimeException(exception); }
        return value.get();
    }

    private static final class Editor implements AutoCloseable {
        final JFrame frame = new JFrame("CopyWords 自动粘贴测试");
        final JTextArea text = new JTextArea();
        final Clipboard system = Toolkit.getDefaultToolkit().getSystemClipboard();
        final Transferable previous = system.getContents(null);
        final boolean caps = Toolkit.getDefaultToolkit().getLockingKeyState(java.awt.event.KeyEvent.VK_CAPS_LOCK);
        Editor() throws Exception {
            SwingUtilities.invokeAndWait(() -> {
                // This fixture tests keyboard transport; IME compatibility is a separate acceptance case.
                text.enableInputMethods(false);
                frame.add(text); frame.setSize(450, 180); frame.setLocationRelativeTo(null); frame.setVisible(true);
            });
            try {
                focus();
                if (caps) Toolkit.getDefaultToolkit().setLockingKeyState(java.awt.event.KeyEvent.VK_CAPS_LOCK, false);
            } catch (Exception | AssertionError exception) { close(); throw exception; }
        }
        void focus() throws Exception {
            SwingUtilities.invokeAndWait(() -> { frame.setAlwaysOnTop(true); frame.toFront(); text.requestFocusInWindow(); });
            HWND window = new HWND(Native.getWindowPointer(frame));
            User32.INSTANCE.SetForegroundWindow(window);
            var position = new AtomicReference<Point>();
            SwingUtilities.invokeAndWait(() -> position.set(text.getLocationOnScreen()));
            var robot = new Robot();
            robot.mouseMove(position.get().x + 30, position.get().y + 25);
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            // Windows may deny SetForegroundWindow; our owned editor's click obtains focus.
            waitFor(() -> window.equals(User32.INSTANCE.GetForegroundWindow()));
            waitFor(() -> InputSimulator.target().known());
            SwingUtilities.invokeAndWait(() -> frame.setAlwaysOnTop(false));
        }
        public void close() throws Exception {
            SwingUtilities.invokeAndWait(frame::dispose);
            if (Toolkit.getDefaultToolkit().getLockingKeyState(java.awt.event.KeyEvent.VK_CAPS_LOCK) != caps)
                Toolkit.getDefaultToolkit().setLockingKeyState(java.awt.event.KeyEvent.VK_CAPS_LOCK, caps);
            if (previous != null) system.setContents(previous, null);
        }
    }

    @Test void writingShortcutsPlaceTypedTextBetweenSymbols() throws Exception {
        var notices = new LinkedBlockingQueue<String>();
        try (var editor = new Editor(); var clipboard = new ClipboardService(notices::add);
             var hotkeys = new GlobalHotkeys(clipboard::paste)) {
            assertTrue(hotkeys.start(new Config(List.of(), false, false, false)).isEmpty());
            int[] keys = {MarkdownShortcuts.MINUS, MarkdownShortcuts.EQUALS, MarkdownShortcuts.BACKSLASH};
            String[] halves = {"`", "**", "*"};
            for (int i = 0; i < keys.length; i++) {
                for (String initial : List.of("", "AB", "AxB")) {
                    SwingUtilities.invokeAndWait(() -> {
                        editor.text.setText(initial);
                        int start = initial.isEmpty() ? 0 : 1;
                        editor.text.select(start, initial.equals("AxB") ? 2 : start);
                    });
                    press(0x14, keys[i]);
                    String message = notices.poll(3, TimeUnit.SECONDS);
                    assertNotNull(message);
                    assertTrue(message.contains("发送粘贴命令"), message);
                    String prefix = initial.isEmpty() ? "" : "A";
                    String suffix = initial.isEmpty() ? "" : "B";
                    String symbols = halves[i] + halves[i];
                    waitFor(() -> editorText(editor.text).equals(prefix + symbols + suffix));
                    int caret = prefix.length() + halves[i].length();
                    waitFor(() -> editorCaret(editor.text) == caret);
                    key('A', true); key('A', false);
                    String expected = prefix + halves[i] + "a" + halves[i] + suffix;
                    waitFor(() -> editorText(editor.text).equals(expected));
                    // Trigger again without resetting the editor to check consecutive insertions.
                    press(0x14, keys[i]);
                    String nextMessage = notices.poll(3, TimeUnit.SECONDS);
                    assertNotNull(nextMessage);
                    assertTrue(nextMessage.contains("发送粘贴命令"), nextMessage);
                    String consecutive = prefix + halves[i] + "a" + symbols + halves[i] + suffix;
                    waitFor(() -> editorText(editor.text).equals(consecutive));
                    int consecutiveCaret = caret + 1 + halves[i].length();
                    waitFor(() -> editorCaret(editor.text) == consecutiveCaret);
                    key('B', true); key('B', false);
                    String withSecondLetter = prefix + halves[i] + "a" + halves[i] + "b" + symbols + suffix;
                    waitFor(() -> editorText(editor.text).equals(withSecondLetter));
                }
            }
        }
    }

    @Test void modifierVariantsWaitForNaturalReleaseAndPasteOnce() throws Exception {
        var notices = new LinkedBlockingQueue<String>(); var count = new AtomicInteger();
        var config = new Config(List.of(Entry.create("A", new Hotkey(Hotkey.ALT, '7')),
                Entry.create("B", new Hotkey(Hotkey.CTRL | Hotkey.SHIFT, '8')),
                Entry.create("C", new Hotkey(Hotkey.CAPS | Hotkey.CTRL, '9'))), false, false, false);
        try (var editor = new Editor(); var clipboard = new ClipboardService(notices::add);
             var hotkeys = new GlobalHotkeys(request -> { count.incrementAndGet(); clipboard.paste(request); })) {
            assertTrue(hotkeys.start(config).isEmpty());
            int[][] variants = {{0xA4, '7'}, {0xA2, 0xA0, '8'}, {0xA3, 0xA1, '8'},
                    {0x14, 0xA2, '9'}, {0x14, 0xA3, '9'}};
            String expected = "";
            for (int[] variant : variants) {
                int before = count.get();
                for (int i = 0; i < variant.length - 1; i++) key(variant[i], true);
                try {
                    key(variant[variant.length - 1], true); key(variant[variant.length - 1], false);
                    waitFor(() -> count.get() == before + 1);
                    assertNull(notices.poll(100, TimeUnit.MILLISECONDS));
                    for (int i = 0; i < variant.length - 1; i++)
                        if (variant[i] != 0x14) assertTrue(User32.INSTANCE.GetAsyncKeyState(variant[i]) < 0);
                } finally {
                    for (int i = variant.length - 2; i >= 0; i--) key(variant[i], false);
                }
                String message = notices.poll(3, TimeUnit.SECONDS);
                assertNotNull(message); assertTrue(message.contains("发送粘贴命令"), message);
                expected += variant[variant.length - 1] == '7' ? "A" : variant[variant.length - 1] == '8' ? "B" : "C";
                String value = expected;
                try { waitFor(() -> editorText(editor.text).equals(value)); }
                catch (AssertionError exception) {
                    throw new AssertionError("variant=" + java.util.Arrays.toString(variant)
                            + ", expected=" + value + ", actual=" + editorText(editor.text), exception);
                }
                key('X', true); key('X', false); expected += "x";
                String withLetter = expected; waitFor(() -> editorText(editor.text).equals(withLetter));
            }
        }
    }

    @Test void altBindingsAndCaptureAcceptOnlyLeftAltEvenAfterFastRelease() throws Exception {
        var requests = new LinkedBlockingQueue<PasteRequest>(); var captured = new LinkedBlockingQueue<Hotkey>();
        var config = new Config(List.of(Entry.create("A", new Hotkey(Hotkey.ALT, '7')),
                Entry.create("B", new Hotkey(Hotkey.CAPS | Hotkey.ALT, '9'))), false, false, false);
        try (var editor = new Editor(); var hotkeys = new GlobalHotkeys(requests::add)) {
            assertTrue(hotkeys.start(config).isEmpty());
            key(0xA5, true); key('7', true); key('7', false); key(0xA5, false);
            assertNull(requests.poll(200, TimeUnit.MILLISECONDS));
            key(0xA4, true); key('7', true); key('7', false); key(0xA4, false);
            assertEquals("A", requests.poll(3, TimeUnit.SECONDS).text());
            key(0xA4, true); key(0xA5, true); key('7', true); key('7', false); key(0xA5, false); key(0xA4, false);
            assertNull(requests.poll(200, TimeUnit.MILLISECONDS));
            key(0x14, true); key(0xA5, true); key('9', true); key('9', false); key(0xA5, false); key(0x14, false);
            assertNull(requests.poll(200, TimeUnit.MILLISECONDS));
            key(0x14, true); key(0xA4, true); key('9', true); key('9', false); key(0xA4, false); key(0x14, false);
            assertEquals("B", requests.poll(3, TimeUnit.SECONDS).text());
            hotkeys.pause(); hotkeys.capture(captured::add);
            key(0x14, true); key(0xA5, true); key('9', true); key('9', false); key(0xA5, false); key(0x14, false);
            assertNull(captured.poll(200, TimeUnit.MILLISECONDS));
            key(0x14, true); key(0xA4, true); key('9', true); key('9', false); key(0xA4, false); key(0x14, false);
            assertEquals(new Hotkey(Hotkey.CAPS | Hotkey.ALT, '9'), captured.poll(3, TimeUnit.SECONDS));
            hotkeys.capture(null);
        }
    }

    @Test void rapidCapsRequestsInsertBothTextsWithoutDigits() throws Exception {
        var notices = new LinkedBlockingQueue<String>();
        var config = new Config(List.of(Entry.create("A", new Hotkey(Hotkey.CAPS, '1')),
                Entry.create("B", new Hotkey(Hotkey.CAPS, '2'))), false, false, false);
        try (var editor = new Editor(); var clipboard = new ClipboardService(notices::add);
             var hotkeys = new GlobalHotkeys(clipboard::paste)) {
            assertTrue(hotkeys.start(config).isEmpty());
            key(0x14, true);
            try {
                key('1', true); Thread.sleep(10); key('1', false);
                key('2', true); Thread.sleep(10); key('2', false);
            } finally { key('1', false); key('2', false); key(0x14, false); }
            waitFor(() -> editorText(editor.text).equals("AB"));
            assertEquals("B", clipboardText());
            assertTrue(notices.poll(3, TimeUnit.SECONDS).contains("发送粘贴命令"));
            assertTrue(notices.poll(3, TimeUnit.SECONDS).contains("发送粘贴命令"));
        }
    }

    @Test void switchingWindowWhileMainKeyIsHeldCancelsInput() throws Exception {
        var notices = new LinkedBlockingQueue<String>(); var triggered = new CountDownLatch(1);
        try (var first = new Editor(); var second = new Editor(); var clipboard = new ClipboardService(notices::add);
             var hotkeys = new GlobalHotkeys(request -> { triggered.countDown(); clipboard.paste(request); })) {
            first.focus(); assertTrue(hotkeys.start(Config.defaults()).isEmpty());
            key(0x14, true); key('1', true);
            try {
                assertTrue(triggered.await(3, TimeUnit.SECONDS)); second.focus();
            } finally { key('1', false); key(0x14, false); }
            String message = notices.poll(3, TimeUnit.SECONDS);
            assertNotNull(message); assertTrue(message.contains("焦点已变化"), message);
            assertEquals("", editorText(first.text)); assertEquals("", editorText(second.text));
        }
    }

    @Test void reservedCtrlVIsSkippedAndManualPasteStillWorks() throws Exception {
        var notices = new LinkedBlockingQueue<String>(); var count = new AtomicInteger();
        var config = new Config(List.of(Entry.create("bad", new Hotkey(Hotkey.CTRL, 'V')),
                Entry.create("A", new Hotkey(Hotkey.CAPS, '1'))), false, false, false);
        try (var editor = new Editor(); var clipboard = new ClipboardService(notices::add);
             var hotkeys = new GlobalHotkeys(request -> { count.incrementAndGet(); clipboard.paste(request); })) {
            var warnings = hotkeys.start(config); assertEquals(1, warnings.size()); assertTrue(warnings.getFirst().contains("Ctrl + V"));
            editor.system.setContents(new StringSelection("outside"), null); press(0xA2, 'V');
            waitFor(() -> editorText(editor.text).equals("outside")); assertEquals(0, count.get());
            press(0x14, '1'); waitFor(() -> editorText(editor.text).equals("outsideA"));
            Thread.sleep(500); assertEquals(1, count.get());
        }
    }

    @Test void externalClipboardChangeCancelsNativePasteAndQueuedWrite() throws Exception {
        var notices = new LinkedBlockingQueue<String>(); var written = new CountDownLatch(1); var release = new CountDownLatch(1);
        var systemClipboard = InputSimulator.clipboard();
        ClipboardService.ClipboardAccess clipboardAccess = new ClipboardService.ClipboardAccess() {
            public void write(String text) {
                systemClipboard.write(text); written.countDown();
                try { if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("test release timeout"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
            }
            public ClipboardService.Snapshot snapshot(String expectedText) { return systemClipboard.snapshot(expectedText); }
        };
        try (var editor = new Editor(); var clipboard = new ClipboardService(clipboardAccess, new InputSimulator(), notices::add);
             var hotkeys = new GlobalHotkeys(clipboard::paste)) {
            assertTrue(hotkeys.start(Config.defaults()).isEmpty());
            try {
                press(0x14, '1'); assertTrue(written.await(3, TimeUnit.SECONDS));
                press(0x14, '2'); editor.system.setContents(new StringSelection("outside"), null);
            } finally { release.countDown(); }
            String message = notices.poll(3, TimeUnit.SECONDS);
            assertNotNull(message); assertTrue(message.contains("剪贴板已被其他程序修改"), message);
            Thread.sleep(200); assertEquals("outside", clipboardText()); assertEquals("", editorText(editor.text));
        } finally { release.countDown(); }
    }

    @Test void actualKeysCopyAcrossWindowsAndReleaseOnExit() throws Exception {
        Clipboard system = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable previous = system.getContents(null);
        var notices = new LinkedBlockingQueue<String>();
        var count = new AtomicInteger();
        int firstKey = '8';
        var otherWindow = new JFrame("CopyWords 测试：其他应用窗口");
        SwingUtilities.invokeAndWait(() -> {
            otherWindow.add(new JLabel("测试全局快捷键，完成后此窗口自动关闭", SwingConstants.CENTER));
            otherWindow.setSize(460, 160); otherWindow.setLocationRelativeTo(null); otherWindow.setVisible(true);
            otherWindow.toFront();
        });
        Thread.sleep(300);
        var defaultConfig = new Config(List.of(Entry.create("AI4S", new Hotkey(Hotkey.ALT, firstKey)),
                Entry.create("人生价值", new Hotkey(6, '2')), Entry.create("xxx@example.com", new Hotkey(Hotkey.ALT, '3'))), false, false, false);
        try (var clipboard = new ClipboardService(notices::add)) {
            try (var service = new GlobalHotkeys(request -> { count.incrementAndGet(); clipboard.paste(request); })) {
                var warnings = service.start(defaultConfig);
                assertTrue(warnings.isEmpty(), String.join("\n", warnings));
                press(0xA4, firstKey);
                assertNotNull(notices.poll(3, TimeUnit.SECONDS)); assertEquals("AI4S", clipboardText());
                press(0xA4, '3');
                assertNotNull(notices.poll(3, TimeUnit.SECONDS)); assertEquals("xxx@example.com", clipboardText());
                key(0xA2, true); key(0xA0, true);
                try { key('2', true); key('2', false); }
                finally { key(0xA0, false); key(0xA2, false); }
                assertNotNull(notices.poll(3, TimeUnit.SECONDS)); assertEquals("人生价值", clipboardText());
                int before = count.get();
                key(0xA4, true); key(firstKey, true);
                try {
                    waitFor(() -> count.get() == before + 1);
                    assertNull(notices.poll(100, TimeUnit.MILLISECONDS), "must wait for modifier release");
                    for (int i = 0; i < 4; i++) { key(firstKey, true); Thread.sleep(100); }
                    assertEquals(before + 1, count.get());
                } finally { key(firstKey, false); key(0xA4, false); }
                assertNotNull(notices.poll(3, TimeUnit.SECONDS));
                clipboard.pause();
                service.pause();
                press(0xA4, firstKey); assertNull(notices.poll(200, TimeUnit.MILLISECONDS));
                assertTrue(service.resume().isEmpty());
                clipboard.resume();
                press(0xA4, firstKey); assertNotNull(notices.poll(3, TimeUnit.SECONDS));
                var changed = new Config(List.of(Entry.create("  保存后的中文  ", new Hotkey(Hotkey.ALT, firstKey))), false, false, false);
                service.apply(changed, () -> new ConfigStore(directory.resolve("config.xml")).save(changed));
                press(0xA4, firstKey); assertNotNull(notices.poll(3, TimeUnit.SECONDS));
                assertEquals("  保存后的中文  ", clipboardText());
                assertEquals(changed, new ConfigStore(directory.resolve("config.xml")).load());
            }
            try (var next = new GlobalHotkeys(text -> { })) { assertTrue(next.start(defaultConfig).isEmpty()); }
        } finally {
            SwingUtilities.invokeAndWait(otherWindow::dispose);
            if (previous != null) system.setContents(previous, null);
        }
    }

    @Test void conflictingAndFailedSavesKeepOldBindings() throws Exception {
        var messages = new LinkedBlockingQueue<String>();
        var original = new Config(List.of(Entry.create("old", new Hotkey(Hotkey.ALT, '8'))), false, false, false);
        var occupied = new Config(List.of(Entry.create("busy", new Hotkey(Hotkey.ALT, '9'))), false, false, false);
        try (var service = new GlobalHotkeys(request -> messages.add(request.text())); var blocker = new GlobalHotkeys(request -> { })) {
            assertTrue(service.start(original).isEmpty()); assertTrue(blocker.start(occupied).isEmpty());
            var candidate = new Config(List.of(Entry.create("new", new Hotkey(Hotkey.ALT, '9'))), false, false, false);
            assertThrows(IllegalStateException.class, () -> service.apply(candidate, () -> fail("must not persist")));
            press(0xA4, '8'); assertEquals("old", messages.poll(3, TimeUnit.SECONDS));
            var staged = new Config(List.of(Entry.create("new", new Hotkey(Hotkey.ALT, '7'))), false, false, false);
            assertThrows(IllegalStateException.class, () -> service.apply(staged, () -> { throw new java.io.IOException("disk failure"); }));
            press(0xA4, '8'); assertEquals("old", messages.poll(3, TimeUnit.SECONDS));
            try (var released = new GlobalHotkeys(text -> { })) { assertTrue(released.start(staged).isEmpty()); }
            var partiallyAvailable = new Config(List.of(Entry.create("blocked", new Hotkey(Hotkey.ALT, '9')),
                    Entry.create("works", new Hotkey(Hotkey.ALT, '6'))), false, false, false);
            try (var partial = new GlobalHotkeys(request -> messages.add(request.text()))) {
                assertEquals(1, partial.start(partiallyAvailable).size());
                press(0xA4, '6'); assertEquals("works", messages.poll(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test void lockedConfigFileKeepsOldContentsAndCleansTemporaryFiles() throws Exception {
        var path = directory.resolve("config.xml"); var store = new ConfigStore(path);
        var old = Config.defaults(); store.save(old);
        try (var handle = FileChannel.open(path, StandardOpenOption.READ, com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)) {
            assertThrows(java.io.IOException.class, () -> store.save(new Config(List.of(), false, false, false)));
            assertEquals(old, store.load());
        }
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void nativeClipboardSnapshotReadsSystemTextAndVersion() throws Exception {
        Clipboard system = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable previous = system.getContents(null);
        var clipboard = InputSimulator.clipboard();
        String text = "  中文😀 & <AI4S>  ";
        try {
            clipboard.write(text);
            ClipboardService.Snapshot first = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (first == null) {
                try { first = clipboard.snapshot(text); }
                catch (IllegalStateException exception) {
                    if (System.nanoTime() >= deadline) throw exception;
                    Thread.sleep(10);
                }
            }
            assertEquals(text, first.text()); assertTrue(first.version() > 0);
            system.setContents(new StringSelection("outside text that must not be pasted"), null);
            var changed = clipboard.snapshot(text);
            assertNotEquals(first.version(), changed.version()); assertNotEquals(text, changed.text());
            system.setContents(new StringSelection("短"), null);
            assertEquals("短", clipboard.snapshot(text).text());
        } finally { if (previous != null) system.setContents(previous, null); }
    }

    @Test void autoStartCanBeEnabledAndDisabledWithoutTouchingRealEntry() {
        var startup = new AutoStart("CopyWords-SMOKE-" + ProcessHandle.current().pid(), AutoStart.command(Path.of("dist/CopyWords.jar")));
        String previous = startup.snapshot();
        try {
            startup.set(true); assertTrue(startup.snapshot().contains("javaw.exe"));
            assertTrue(startup.snapshot().contains("--enable-native-access=ALL-UNNAMED"));
            startup.set(false); assertNull(startup.snapshot());
        } finally { startup.restore(previous); }
    }

    @Test void capsCopiesAndPastesWithoutTypingDigitsOrChangingCase() throws Exception {
        var notices = new LinkedBlockingQueue<String>(); var count = new AtomicInteger();
        Clipboard system = Toolkit.getDefaultToolkit().getSystemClipboard(); Transferable previous = system.getContents(null);
        var frame = new JFrame("CopyWords Caps 测试"); var editor = new JTextArea();
        SwingUtilities.invokeAndWait(() -> {
            frame.add(editor); frame.setSize(450, 180); frame.setLocationRelativeTo(null); frame.setVisible(true);
            frame.toFront(); editor.requestFocusInWindow();
        });
        Thread.sleep(200);
        User32.INSTANCE.SetForegroundWindow(new HWND(Native.getWindowPointer(frame)));
        assertEquals(new HWND(Native.getWindowPointer(frame)), User32.INSTANCE.GetForegroundWindow(), "test editor must have foreground focus");
        boolean initialCaps = Toolkit.getDefaultToolkit().getLockingKeyState(java.awt.event.KeyEvent.VK_CAPS_LOCK);
        try (var clipboard = new ClipboardService(notices::add);
             var service = new GlobalHotkeys(request -> { count.incrementAndGet(); clipboard.paste(request); })) {
            assertTrue(service.start(Config.defaults()).isEmpty());
            press(0x14, '1'); assertNotNull(notices.poll(3, TimeUnit.SECONDS)); assertEquals("AI4S", clipboardText());
            waitFor(() -> editorText(editor).equals("AI4S"));
            assertEquals(initialCaps, Toolkit.getDefaultToolkit().getLockingKeyState(java.awt.event.KeyEvent.VK_CAPS_LOCK));
            SwingUtilities.invokeAndWait(() -> assertEquals("AI4S", editor.getText()));
            int before = count.get(); key(0x14, true); key('2', true);
            try {
                waitFor(() -> count.get() == before + 1);
                assertNull(notices.poll(100, TimeUnit.MILLISECONDS), "must wait for main key release");
                for (int i = 0; i < 3; i++) { key('2', true); Thread.sleep(80); }
                assertEquals(before + 1, count.get());
            } finally { key('2', false); key(0x14, false); }
            assertNotNull(notices.poll(3, TimeUnit.SECONDS)); assertEquals("人生价值", clipboardText());
            waitFor(() -> editorText(editor).equals("AI4S人生价值"));
            key(0x14, true); key(0x14, false); Thread.sleep(200);
            assertEquals(!initialCaps, Toolkit.getDefaultToolkit().getLockingKeyState(java.awt.event.KeyEvent.VK_CAPS_LOCK), "standalone Caps retains toggle");
            key(0x14, true); key(0x14, false); Thread.sleep(200);
            assertEquals(initialCaps, Toolkit.getDefaultToolkit().getLockingKeyState(java.awt.event.KeyEvent.VK_CAPS_LOCK));
            service.apply(new Config(List.of(Entry.create("  新内容  ", new Hotkey(Hotkey.CAPS, '1'))), false, false, false), () -> { });
            press(0x14, '1'); assertNotNull(notices.poll(3, TimeUnit.SECONDS)); assertEquals("  新内容  ", clipboardText());
            clipboard.pause(); service.pause(); press(0x14, '1'); assertNull(notices.poll(200, TimeUnit.MILLISECONDS));
            assertTrue(service.resume().isEmpty());
            clipboard.resume();
            press(0x14, '1'); assertNotNull(notices.poll(3, TimeUnit.SECONDS));
        } finally {
            if (Toolkit.getDefaultToolkit().getLockingKeyState(java.awt.event.KeyEvent.VK_CAPS_LOCK) != initialCaps)
                Toolkit.getDefaultToolkit().setLockingKeyState(java.awt.event.KeyEvent.VK_CAPS_LOCK, initialCaps);
            SwingUtilities.invokeAndWait(frame::dispose); if (previous != null) system.setContents(previous, null);
        }
    }
}
