import cn.copywords.*;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.*;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.lang.reflect.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.text.AttributedString;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

// Exercises real Swing listeners and native commits using disposable profiles only.
class AutoSaveSmoke {
    static final List<String> report = new ArrayList<>();
    static final Hotkey FIRST_KEY = new Hotkey(Hotkey.CAPS, KeyEvent.VK_F9);
    static final Hotkey SECOND_KEY = new Hotkey(Hotkey.CAPS, KeyEvent.VK_F10);

    static Object field(Object target, String name) throws Exception {
        Field value = target.getClass().getDeclaredField(name);
        value.setAccessible(true); return value.get(target);
    }

    static Object invoke(Object target, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true); return method.invoke(target, arguments);
    }

    static <T> T edt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task); return task.get();
    }

    static void waitFor(Callable<Boolean> condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.call()) {
            if (System.nanoTime() >= deadline) throw new AssertionError(message);
            Thread.sleep(30);
        }
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        report.add("PASS " + message);
    }

    static AbstractButton button(Container parent, String label) {
        for (Component component : parent.getComponents()) {
            if (component instanceof AbstractButton candidate && label.equals(candidate.getText())) return candidate;
            if (component instanceof Container nested) {
                AbstractButton found = button(nested, label);
                if (found != null) return found;
            }
        }
        return null;
    }

    static JDialog dialog(String title) {
        for (Window window : Window.getWindows())
            if (window instanceof JDialog candidate && candidate.isShowing() && title.equals(candidate.getTitle())) return candidate;
        return null;
    }

    static final class Profile implements AutoCloseable {
        final Path home = Files.createTempDirectory(Path.of("temp").toAbsolutePath(), "autosave-");
        final Path config = home.resolve("config.xml");
        final ConfigStore store = new ConfigStore(config);
        final AutoStart startup;
        final String previousStartup;
        final FileChannel channel;
        final FileLock lock;
        final Object app;
        final JFrame frame;

        Profile(String mode) throws Exception {
            String startupName = "CopyWords-AUTOSAVE-SMOKE-" + UUID.randomUUID();
            System.setProperty("copywords.autostartName", startupName);
            startup = new AutoStart(startupName, AutoStart.command(Path.of("dist/CopyWords.jar")));
            previousStartup = startup.snapshot();
            if (mode.equals("corrupt")) Files.writeString(config, "broken xml");
            else if (!mode.equals("fresh")) {
                Hotkey first = mode.equals("legacy") ? new Hotkey(Hotkey.ALT, '1') : FIRST_KEY;
                store.save(new Config(List.of(Entry.create("原内容", first), Entry.create("第二行", SECOND_KEY)), false, false, false));
                if (mode.equals("legacy")) {
                    var properties = new Properties();
                    try (var input = Files.newInputStream(config)) { properties.loadFromXML(input); }
                    properties.remove("capsDefaultsVersion");
                    try (var output = Files.newOutputStream(config)) { properties.storeToXML(output, "legacy", "UTF-8"); }
                }
            }
            channel = FileChannel.open(home.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            var created = new CompletableFuture<Object>();
            SwingUtilities.invokeLater(() -> {
                try {
                    Constructor<?> constructor = CopyWords.class.getDeclaredConstructor(Path.class, FileChannel.class, FileLock.class);
                    constructor.setAccessible(true); created.complete(constructor.newInstance(home, channel, lock));
                } catch (Throwable exception) { created.completeExceptionally(exception); }
            });
            if (mode.equals("corrupt")) {
                waitFor(() -> dialog("CopyWords") != null, "corrupt configuration warning missing");
                edt(() -> { dialog("CopyWords").dispose(); return null; });
            }
            app = created.get(8, TimeUnit.SECONDS);
            frame = (JFrame) field(app, "frame");
        }

        Object row(int index) throws Exception { return edt(() -> ((List<?>) field(app, "rows")).get(index)); }
        JTextField text(int index) throws Exception { return (JTextField) field(row(index), "text"); }
        JCheckBox option(String name) throws Exception { return (JCheckBox) field(app, name); }
        void setText(int index, String value, boolean enter) throws Exception {
            JTextField input = text(index);
            edt(() -> { input.setText(value); if (enter) input.postActionEvent(); return null; });
        }
        void awaitSaved() throws Exception {
            waitFor(() -> edt(() -> !(Boolean) field(app, "saving") && !(Boolean) field(app, "pendingSave")
                    && !((javax.swing.Timer) field(app, "saveTimer")).isRunning()
                    && !(Boolean) field(app, "capturing") && Files.exists(config)
                    && store.load().equals(field(app, "saved"))), "automatic save did not settle: " + home);
        }
        void awaitFailure() throws Exception {
            waitFor(() -> edt(() -> ((JButton) field(app, "retry")).isVisible()
                    && !(Boolean) field(app, "saving")), "automatic save failure missing");
        }
        void capture(int index) throws Exception {
            JButton shortcut = (JButton) field(row(index), "shortcut");
            SwingUtilities.invokeLater(shortcut::doClick);
            waitFor(() -> dialog("设置快捷键") != null, "shortcut capture did not open");
        }
        void finishCapture(String outcome) throws Exception {
            edt(() -> { button(dialog("设置快捷键"), outcome).doClick(); return null; });
            waitFor(() -> edt(() -> !(Boolean) field(app, "capturing")), "shortcut capture did not finish");
        }
        String bindingText(Hotkey key) throws Exception {
            Object hotkeys = field(app, "hotkeys");
            return (String) invoke(hotkeys, "invoke", new Class<?>[] {Callable.class}, (Callable<String>) () -> {
                Object binding = ((Map<?, ?>) field(hotkeys, "bindings")).get(key);
                return binding != null && (Boolean) field(binding, "registered") ? ((Entry) field(binding, "entry")).text() : null;
            });
        }
        @Override public void close() throws Exception {
            edt(() -> { ((javax.swing.Timer) field(app, "saveTimer")).stop(); return null; });
            waitFor(() -> edt(() -> !(Boolean) field(app, "saving")), "save worker did not stop before cleanup");
            ((GlobalHotkeys) field(app, "hotkeys")).close();
            ((ClipboardService) field(app, "clipboard")).close();
            edt(() -> {
                TrayIcon tray = (TrayIcon) field(app, "tray");
                if (tray != null) SystemTray.getSystemTray().remove(tray);
                JDialog owner = (JDialog) field(app, "trayMenuOwner");
                if (owner != null) owner.dispose();
                frame.dispose(); return null;
            });
            startup.restore(previousStartup);
            lock.release(); channel.close();
        }
    }

    static final class NativeGate implements AutoCloseable {
        final CountDownLatch release = new CountDownLatch(1);
        NativeGate(Profile profile) throws Exception {
            Object hotkeys = field(profile.app, "hotkeys");
            var entered = new CountDownLatch(1);
            @SuppressWarnings("unchecked") var commands = (Queue<Runnable>) field(hotkeys, "commands");
            commands.add(() -> {
                entered.countDown();
                try { if (!release.await(8, TimeUnit.SECONDS)) throw new AssertionError("native test gate timed out"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            });
            User32.INSTANCE.PostThreadMessage((Integer) field(hotkeys, "threadId"), 0x8001, new WPARAM(0), new LPARAM(0));
            if (!entered.await(3, TimeUnit.SECONDS)) throw new AssertionError("native test gate did not start");
        }
        @Override public void close() { release.countDown(); }
    }

    static void textAndOptions() throws Exception {
        try (var profile = new Profile("seeded")) {
            byte[] original = Files.readAllBytes(profile.config);
            Thread.sleep(600);
            check(Arrays.equals(original, Files.readAllBytes(profile.config)), "loading does not rewrite configuration");
            check(button(profile.frame, "保存") == null, "manual save button absent");
            profile.setText(0, "第一段", false);
            Thread.sleep(150);
            profile.setText(0, "  连续输入中文  ", false);
            Thread.sleep(200);
            check(Arrays.equals(original, Files.readAllBytes(profile.config)), "continuous input resets 500 ms debounce");
            profile.awaitSaved();
            check("  连续输入中文  ".equals(profile.store.load().entries().getFirst().text()), "Chinese and surrounding spaces saved");
            check("  连续输入中文  ".equals(profile.bindingText(FIRST_KEY)), "saved text updates native binding");
            profile.startup.restore("test registration sentinel");
            profile.setText(0, "后续内容", true);
            profile.awaitSaved();
            check("test registration sentinel".equals(profile.startup.snapshot()), "text-only saves do not rewrite startup registration");
            JTextField input = profile.text(0);
            edt(() -> {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection("粘贴内容"), null);
                input.selectAll(); input.paste(); return null;
            });
            profile.awaitSaved();
            check("粘贴内容".equals(profile.store.load().entries().getFirst().text()), "clipboard paste automatically saved");
            profile.setText(0, "", false);
            Thread.sleep(600);
            check("粘贴内容".equals(profile.store.load().entries().getFirst().text()), "temporary empty text not saved");
            JCheckBox startHidden = profile.option("startHidden");
            edt(() -> { startHidden.setSelected(true); return null; });
            profile.awaitSaved();
            check("粘贴内容".equals(profile.store.load().entries().getFirst().text()), "another setting cannot commit temporary empty text");
            edt(() -> { input.postActionEvent(); return null; });
            profile.awaitSaved();
            check(profile.store.load().entries().getFirst().text().isEmpty() && profile.bindingText(FIRST_KEY) == null,
                    "Enter confirms empty text and disables binding without clearing shortcut");
            profile.setText(0, "失焦后提交", true);
            profile.awaitSaved();
            profile.setText(0, "", false);
            edt(() -> {
                for (FocusListener listener : input.getFocusListeners())
                    listener.focusLost(new FocusEvent(input, FocusEvent.FOCUS_LOST));
                return null;
            });
            profile.awaitSaved();
            check(profile.store.load().entries().getFirst().text().isEmpty() && profile.bindingText(FIRST_KEY) == null,
                    "focus loss confirms empty text and disables binding");
            profile.setText(0, "   ", true);
            profile.awaitSaved();
            check("   ".equals(profile.bindingText(FIRST_KEY)), "intentional whitespace remains active");
            edt(() -> { profile.option("autoStart").setSelected(true); return null; });
            profile.awaitSaved();
            check(profile.startup.snapshot() != null && !profile.startup.snapshot().equals("test registration sentinel"), "startup option updates registration");
            edt(() -> { profile.option("autoStart").setSelected(false); return null; });
            profile.awaitSaved();
            check(profile.startup.snapshot() == null, "disabling startup removes test registration");
        }
    }

    static void blankRowsAndCapture() throws Exception {
        try (var profile = new Profile("seeded")) {
            byte[] original = Files.readAllBytes(profile.config);
            edt(() -> { button(profile.frame, "+ 添加").doClick(); return null; });
            Thread.sleep(600);
            check(Arrays.equals(original, Files.readAllBytes(profile.config)), "adding blank row does not save");
            profile.setText(0, "其他条目更新", true);
            profile.awaitSaved();
            check(profile.store.load().entries().size() == 2, "other saves exclude unconfigured new blank row");
            original = Files.readAllBytes(profile.config);
            profile.capture(2);
            profile.setText(0, "录入期间的修改", false);
            Thread.sleep(600);
            check(Arrays.equals(original, Files.readAllBytes(profile.config)), "saving paused throughout shortcut capture");
            check(profile.bindingText(FIRST_KEY) == null, "native binding remains suspended throughout capture");
            profile.finishCapture("确定");
            profile.awaitSaved();
            Entry blank = profile.store.load().entries().getLast();
            check(blank.text().isEmpty() && blank.hotkey() != null && !blank.active(), "confirmed shortcut persists on empty inactive row");
            check("录入期间的修改".equals(profile.bindingText(FIRST_KEY)), "pending text saved after capture resumes");
            Hotkey originalKey = profile.store.load().entries().getFirst().hotkey();
            profile.capture(0);
            profile.finishCapture("取消");
            check(originalKey.equals(profile.store.load().entries().getFirst().hotkey()), "cancel preserves shortcut");
            profile.capture(0);
            profile.finishCapture("清除");
            profile.awaitSaved();
            check(profile.bindingText(FIRST_KEY) == null && profile.store.load().entries().getFirst().hotkey() == null,
                    "clearing shortcut persists and unregisters binding");
            Object second = profile.row(1);
            edt(() -> { button((Container) field(second, "panel"), "删除").doClick(); return null; });
            profile.awaitSaved();
            check(profile.store.load().entries().size() == 2 && profile.bindingText(SECOND_KEY) == null, "deleting existing row persists and unregisters binding");
        }
    }

    static void concurrentEdits() throws Exception {
        try (var profile = new Profile("seeded")) {
            try (var gate = new NativeGate(profile)) {
                profile.setText(0, "正在提交的快照", true);
                waitFor(() -> edt(() -> (Boolean) field(profile.app, "saving")), "save did not begin behind gate");
                check(profile.frame.isEnabled(), "ordinary saving keeps editor enabled");
                profile.setText(0, "最终内容", true);
                Object deleted = profile.row(1);
                edt(() -> { button((Container) field(deleted, "panel"), "删除").doClick(); return null; });
                JButton shortcut = (JButton) field(profile.row(0), "shortcut");
                edt(() -> { shortcut.doClick(); return null; });
                check(dialog("设置快捷键") == null, "capture deferred until active save finishes");
            }
            waitFor(() -> dialog("设置快捷键") != null, "deferred capture did not open");
            profile.finishCapture("取消");
            profile.awaitSaved();
            check(profile.store.load().entries().size() == 1 && "最终内容".equals(profile.store.load().entries().getFirst().text()),
                    "latest edit and deletion survive earlier save completion");
            check("最终内容".equals(profile.bindingText(FIRST_KEY)) && profile.bindingText(SECOND_KEY) == null,
                    "final native bindings match latest persisted snapshot");
        }
    }

    static void failuresAndClose() throws Exception {
        try (var profile = new Profile("seeded")) {
            byte[] original = Files.readAllBytes(profile.config);
            try (var held = FileChannel.open(profile.config, StandardOpenOption.READ, com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)) {
                profile.setText(0, "保存失败的草稿", true);
                profile.awaitFailure();
                check(Arrays.equals(original, Files.readAllBytes(profile.config)) && "原内容".equals(profile.bindingText(FIRST_KEY)),
                        "locked configuration preserves previous file and binding");
                Thread.sleep(600);
                check(edt(() -> !(Boolean) field(profile.app, "saving")), "failed snapshot is not retried in a loop");
                edt(() -> { profile.frame.dispatchEvent(new WindowEvent(profile.frame, WindowEvent.WINDOW_CLOSING)); return null; });
                waitFor(() -> dialog("CopyWords") != null, "failed exit confirmation missing");
                edt(() -> { button(dialog("CopyWords"), "取消").doClick(); return null; });
                check(profile.frame.isVisible() && profile.frame.isEnabled(), "canceling failed exit restores editable window");
            }
            edt(() -> { ((JButton) field(profile.app, "retry")).doClick(); return null; });
            profile.awaitSaved();
            check("保存失败的草稿".equals(profile.bindingText(FIRST_KEY)), "retry commits preserved draft after file unlocked");
            edt(() -> {
                profile.option("closeToTray").setSelected(true);
                profile.text(0).setText("关闭前最后修改");
                profile.frame.dispatchEvent(new WindowEvent(profile.frame, WindowEvent.WINDOW_CLOSING)); return null;
            });
            waitFor(() -> !profile.frame.isVisible(), "close-to-tray did not flush and hide");
            check("关闭前最后修改".equals(profile.store.load().entries().getFirst().text()), "immediate close flushes final text before hiding");
            edt(() -> { profile.frame.setVisible(true); return null; });
            original = Files.readAllBytes(profile.config);
            try (var held = FileChannel.open(profile.config, StandardOpenOption.READ, com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)) {
                edt(() -> {
                    profile.text(0).setText("隐藏失败时保留的草稿");
                    profile.frame.dispatchEvent(new WindowEvent(profile.frame, WindowEvent.WINDOW_CLOSING)); return null;
                });
                waitFor(() -> dialog("CopyWords") != null, "failed hide feedback missing");
                edt(() -> { dialog("CopyWords").dispose(); return null; });
                check(profile.frame.isVisible() && profile.frame.isEnabled() && Arrays.equals(original, Files.readAllBytes(profile.config)),
                        "failed hide retains visible editable window and previous file");
            }
            edt(() -> { ((JButton) field(profile.app, "retry")).doClick(); return null; });
            profile.awaitSaved();
        }
    }

    static void shortcutConflict() throws Exception {
        try (var profile = new Profile("seeded")) {
            Hotkey occupied = new Hotkey(Hotkey.CTRL | Hotkey.SHIFT, KeyEvent.VK_F11);
            byte[] original = Files.readAllBytes(profile.config);
            try (var blocker = new GlobalHotkeys(request -> { })) {
                check(blocker.start(new Config(List.of(Entry.create("占用", occupied)), false, false, false)).isEmpty(),
                        "test shortcut blocker registered");
                profile.capture(0);
                // Deliver a Swing key event because the blocker consumes this key at the OS level.
                edt(() -> {
                    KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(new KeyEvent(dialog("设置快捷键"),
                            KeyEvent.KEY_PRESSED, System.currentTimeMillis(), KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK,
                            KeyEvent.VK_F11, KeyEvent.CHAR_UNDEFINED));
                    return null;
                });
                check(edt(() -> occupied.toString().equals(((JLabel) dialog("设置快捷键").getContentPane().getComponent(1)).getText())),
                        "shortcut capture receives the conflicting candidate");
                profile.finishCapture("确定");
                profile.awaitFailure();
                check(occupied.equals(field(profile.row(0), "hotkey")), "conflicting captured shortcut retained in draft");
                check(Arrays.equals(original, Files.readAllBytes(profile.config)) && "原内容".equals(profile.bindingText(FIRST_KEY)),
                        "shortcut conflict preserves previous file and binding");
                check(profile.text(0).getToolTipText().contains("占用"), "conflicting row identified with actionable reason");
            }
            edt(() -> { ((JButton) field(profile.app, "retry")).doClick(); return null; });
            profile.awaitSaved();
            check("原内容".equals(profile.bindingText(occupied)) && profile.bindingText(FIRST_KEY) == null,
                    "retry succeeds after conflicting shortcut released");
        }
    }

    static void compositionAndCompatibility() throws Exception {
        try (var profile = new Profile("seeded")) {
            JTextField input = profile.text(0);
            byte[] original = Files.readAllBytes(profile.config);
            edt(() -> {
                InputMethodEvent event = new InputMethodEvent(input, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                        new AttributedString("候选").getIterator(), 0, null, null);
                for (InputMethodListener listener : input.getInputMethodListeners()) listener.inputMethodTextChanged(event);
                input.setText("候选"); return null;
            });
            Thread.sleep(600);
            check(Arrays.equals(original, Files.readAllBytes(profile.config)), "uncommitted IME composition does not save");
            edt(() -> {
                input.setText("已确认中文");
                InputMethodEvent event = new InputMethodEvent(input, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                        new AttributedString("已确认中文").getIterator(), 5, null, null);
                for (InputMethodListener listener : input.getInputMethodListeners()) listener.inputMethodTextChanged(event);
                return null;
            });
            profile.awaitSaved();
            check("已确认中文".equals(profile.store.load().entries().getFirst().text()), "confirmed IME input automatically saved");
        }
        try (var profile = new Profile("fresh")) {
            Thread.sleep(600);
            check(!Files.exists(profile.config) && profile.startup.snapshot() == null, "untouched first launch does not save or register startup");
        }
        try (var profile = new Profile("corrupt")) {
            Thread.sleep(600);
            check("broken xml".equals(Files.readString(profile.config)) && profile.startup.snapshot() == null,
                    "corrupt file preserved during startup");
        }
        try (var profile = new Profile("legacy")) {
            profile.awaitSaved();
            var properties = new Properties();
            try (var input = Files.newInputStream(profile.config)) { properties.loadFromXML(input); }
            check(properties.containsKey("capsDefaultsVersion") && new Hotkey(Hotkey.CAPS, '1').equals(profile.store.load().entries().getFirst().hotkey())
                    && profile.startup.snapshot() == null, "legacy migration automatically persisted without startup registration");
        }
    }

    public static void main(String[] args) throws Exception {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable previous = clipboard.getContents(null);
        String previousName = System.getProperty("copywords.autostartName");
        int result = 0;
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            textAndOptions(); blankRowsAndCapture(); concurrentEdits(); failuresAndClose(); shortcutConflict(); compositionAndCompatibility();
        } catch (Throwable exception) {
            result = 1; report.add("FAIL " + exception); exception.printStackTrace();
        } finally {
            if (previous != null) clipboard.setContents(previous, null);
            if (previousName == null) System.clearProperty("copywords.autostartName");
            else System.setProperty("copywords.autostartName", previousName);
            Files.write(Path.of("logs/autosave-smoke-report.txt"), report, java.nio.charset.StandardCharsets.UTF_8);
        }
        System.out.println(String.join("\n", report));
        System.exit(result);
    }
}
