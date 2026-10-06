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
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "copywords.windowsSmoke", matches = "true")
class WindowsSmokeTest {
    @TempDir Path directory;

    static void key(int key, boolean down) {
        var input = new WinUser.INPUT();
        input.type = new DWORD(WinUser.INPUT.INPUT_KEYBOARD);
        input.input.setType("ki");
        input.input.ki = new WinUser.KEYBDINPUT();
        input.input.ki.wVk = new WORD(key);
        input.input.ki.wScan = new WORD(User32.INSTANCE.MapVirtualKeyEx(key, 4, User32.INSTANCE.GetKeyboardLayout(0)) & 0xFF);
        input.input.ki.dwFlags = new DWORD((down ? 0 : WinUser.KEYBDINPUT.KEYEVENTF_KEYUP)
                | (key == 0xA5 ? WinUser.KEYBDINPUT.KEYEVENTF_EXTENDEDKEY : 0));
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

    @Test void actualKeysCopyAcrossWindowsAndReleaseOnExit() throws Exception {
        Clipboard system = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable previous = system.getContents(null);
        var notices = new LinkedBlockingQueue<String>();
        var count = new AtomicInteger();
        var otherWindow = new JFrame("CopyWords 测试：其他应用窗口");
        SwingUtilities.invokeAndWait(() -> {
            otherWindow.add(new JLabel("测试全局快捷键，完成后此窗口自动关闭", SwingConstants.CENTER));
            otherWindow.setSize(460, 160); otherWindow.setLocationRelativeTo(null); otherWindow.setVisible(true);
            otherWindow.toFront();
        });
        Thread.sleep(300);
        var defaultConfig = new Config(List.of(Entry.create("AI4S", new Hotkey(Hotkey.ALT, '1')),
                Entry.create("人生价值", new Hotkey(6, '2')), Entry.create("xxx@example.com", new Hotkey(Hotkey.ALT, '3'))), false, false);
        try (var clipboard = new ClipboardService(notices::add)) {
            try (var service = new GlobalHotkeys(text -> { count.incrementAndGet(); clipboard.copy(text); })) {
                assertTrue(service.start(defaultConfig).isEmpty());
                press(0xA4, '1');
                assertNotNull(notices.poll(3, TimeUnit.SECONDS)); assertEquals("AI4S", clipboardText());
                press(0xA5, '3');
                assertNotNull(notices.poll(3, TimeUnit.SECONDS)); assertEquals("xxx@example.com", clipboardText());
                key(0xA2, true); key(0xA0, true);
                try { key('2', true); key('2', false); }
                finally { key(0xA0, false); key(0xA2, false); }
                assertNotNull(notices.poll(3, TimeUnit.SECONDS)); assertEquals("人生价值", clipboardText());
                int before = count.get();
                key(0xA4, true); key('1', true);
                try {
                    assertNotNull(notices.poll(3, TimeUnit.SECONDS));
                    for (int i = 0; i < 4; i++) { key('1', true); Thread.sleep(100); }
                    assertEquals(before + 1, count.get());
                } finally { key('1', false); key(0xA4, false); }
                service.pause();
                press(0xA4, '1'); assertNull(notices.poll(200, TimeUnit.MILLISECONDS));
                assertTrue(service.resume().isEmpty());
                press(0xA4, '1'); assertNotNull(notices.poll(3, TimeUnit.SECONDS));
                var changed = new Config(List.of(Entry.create("  保存后的中文  ", new Hotkey(Hotkey.ALT, '1'))), false, false);
                service.apply(changed, () -> new ConfigStore(directory.resolve("config.xml")).save(changed));
                press(0xA4, '1'); assertNotNull(notices.poll(3, TimeUnit.SECONDS));
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
        var original = new Config(List.of(Entry.create("old", new Hotkey(Hotkey.ALT, '8'))), false, false);
        var occupied = new Config(List.of(Entry.create("busy", new Hotkey(Hotkey.ALT, '9'))), false, false);
        try (var service = new GlobalHotkeys(messages::add); var blocker = new GlobalHotkeys(text -> { })) {
            assertTrue(service.start(original).isEmpty()); assertTrue(blocker.start(occupied).isEmpty());
            var candidate = new Config(List.of(Entry.create("new", new Hotkey(Hotkey.ALT, '9'))), false, false);
            assertThrows(IllegalStateException.class, () -> service.apply(candidate, () -> fail("must not persist")));
            press(0xA4, '8'); assertEquals("old", messages.poll(3, TimeUnit.SECONDS));
            var staged = new Config(List.of(Entry.create("new", new Hotkey(Hotkey.ALT, '7'))), false, false);
            assertThrows(IllegalStateException.class, () -> service.apply(staged, () -> { throw new java.io.IOException("disk failure"); }));
            press(0xA4, '8'); assertEquals("old", messages.poll(3, TimeUnit.SECONDS));
            try (var released = new GlobalHotkeys(text -> { })) { assertTrue(released.start(staged).isEmpty()); }
            var partiallyAvailable = new Config(List.of(Entry.create("blocked", new Hotkey(Hotkey.ALT, '9')),
                    Entry.create("works", new Hotkey(Hotkey.ALT, '6'))), false, false);
            try (var partial = new GlobalHotkeys(messages::add)) {
                assertEquals(1, partial.start(partiallyAvailable).size());
                press(0xA4, '6'); assertEquals("works", messages.poll(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test void lockedConfigFileKeepsOldContentsAndCleansTemporaryFiles() throws Exception {
        var path = directory.resolve("config.xml"); var store = new ConfigStore(path);
        var old = Config.defaults(); store.save(old);
        try (var handle = FileChannel.open(path, StandardOpenOption.READ, com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)) {
            assertThrows(java.io.IOException.class, () -> store.save(new Config(List.of(), false, false)));
            assertEquals(old, store.load());
        }
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
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
             var service = new GlobalHotkeys(text -> { count.incrementAndGet(); clipboard.copy(text); })) {
            assertTrue(service.start(Config.defaults()).isEmpty());
            press(0x14, '1'); assertNotNull(notices.poll(3, TimeUnit.SECONDS)); assertEquals("AI4S", clipboardText());
            Thread.sleep(100);
            assertEquals(initialCaps, Toolkit.getDefaultToolkit().getLockingKeyState(java.awt.event.KeyEvent.VK_CAPS_LOCK));
            SwingUtilities.invokeAndWait(() -> assertEquals("", editor.getText(), "Caps digit must not type into the editor"));
            press(0xA2, 'V'); Thread.sleep(200);
            SwingUtilities.invokeAndWait(() -> assertEquals("AI4S", editor.getText()));
            int before = count.get(); key(0x14, true); key('2', true);
            try {
                assertNotNull(notices.poll(3, TimeUnit.SECONDS));
                for (int i = 0; i < 3; i++) { key('2', true); Thread.sleep(80); }
                assertEquals(before + 1, count.get()); assertEquals("人生价值", clipboardText());
            } finally { key('2', false); key(0x14, false); }
            key(0x14, true); key(0x14, false); Thread.sleep(200);
            assertEquals(!initialCaps, Toolkit.getDefaultToolkit().getLockingKeyState(java.awt.event.KeyEvent.VK_CAPS_LOCK), "standalone Caps retains toggle");
            key(0x14, true); key(0x14, false); Thread.sleep(200);
            assertEquals(initialCaps, Toolkit.getDefaultToolkit().getLockingKeyState(java.awt.event.KeyEvent.VK_CAPS_LOCK));
            service.apply(new Config(List.of(Entry.create("  新内容  ", new Hotkey(Hotkey.CAPS, '1'))), false, false), () -> { });
            press(0x14, '1'); assertNotNull(notices.poll(3, TimeUnit.SECONDS)); assertEquals("  新内容  ", clipboardText());
            service.pause(); press(0x14, '1'); assertNull(notices.poll(200, TimeUnit.MILLISECONDS));
            assertTrue(service.resume().isEmpty());
            press(0x14, '1'); assertNotNull(notices.poll(3, TimeUnit.SECONDS));
        } finally {
            if (Toolkit.getDefaultToolkit().getLockingKeyState(java.awt.event.KeyEvent.VK_CAPS_LOCK) != initialCaps)
                Toolkit.getDefaultToolkit().setLockingKeyState(java.awt.event.KeyEvent.VK_CAPS_LOCK, initialCaps);
            SwingUtilities.invokeAndWait(frame::dispose); if (previous != null) system.setContents(previous, null);
        }
    }
}
