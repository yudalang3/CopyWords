import cn.copywords.*;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.ptr.IntByReference;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.*;
import java.lang.reflect.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

// Exercises our Swing application in an isolated profile. Run with the packaged JAR on the classpath.
public class UiSmoke {
    static Object app;
    static JFrame frame;
    static Robot robot;
    static final List<String> report = new ArrayList<>();

    static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    static void onEdt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        report.add("PASS " + message);
    }
    static void waitFor(BooleanSupplier predicate, String message) throws Exception {
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (!predicate.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError(message);
            Thread.sleep(50);
        }
    }
    static List<?> rows() throws Exception { return (List<?>) field(app, "rows"); }
    static AbstractButton button(Container parent, String text) {
        for (Component component : parent.getComponents()) {
            if (component instanceof AbstractButton candidate && text.equals(candidate.getText())) return candidate;
            if (component instanceof Container nested) {
                AbstractButton found = button(nested, text); if (found != null) return found;
            }
        }
        return null;
    }
    static JDialog dialog(String title) {
        for (Window window : Window.getWindows())
            if (window instanceof JDialog candidate && candidate.isShowing() && title.equals(candidate.getTitle())) return candidate;
        return null;
    }
    static void save(Path config) throws Exception {
        onEdt(() -> button(frame, "保存").doClick());
        waitFor(() -> {
            try { return !(Boolean) field(app, "saving") && Files.exists(config); }
            catch (Exception exception) { throw new RuntimeException(exception); }
        }, "save did not finish");
    }
    static void capture(Object row, String outcome) throws Exception {
        var shortcut = (JButton) field(row, "shortcut");
        SwingUtilities.invokeLater(shortcut::doClick);
        waitFor(() -> dialog("设置快捷键") != null, "capture dialog missing");
        robot.keyPress(KeyEvent.VK_CAPS_LOCK); robot.keyPress(KeyEvent.VK_1);
        robot.keyRelease(KeyEvent.VK_1); robot.keyRelease(KeyEvent.VK_CAPS_LOCK);
        Thread.sleep(150);
        check("capture sentinel".equals(Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor)),
                "hotkeys paused during capture");
        if (outcome.equals("确定")) {
            robot.keyPress(KeyEvent.VK_CONTROL); robot.keyPress(KeyEvent.VK_SHIFT); robot.keyPress(KeyEvent.VK_4);
            robot.keyRelease(KeyEvent.VK_4); robot.keyRelease(KeyEvent.VK_SHIFT); robot.keyRelease(KeyEvent.VK_CONTROL);
            Thread.sleep(150);
        }
        onEdt(() -> button(dialog("设置快捷键"), outcome).doClick());
        waitFor(() -> dialog("设置快捷键") == null, "capture did not close");
    }
    static void screenshot(String suffix) throws Exception {
        onEdt(() -> {
            try {
                JComponent content = (JComponent) frame.getContentPane();
                double scale = frame.getGraphicsConfiguration().getDefaultTransform().getScaleX();
                var image = new java.awt.image.BufferedImage((int) Math.ceil(content.getWidth() * scale),
                        (int) Math.ceil(content.getHeight() * scale), java.awt.image.BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics(); graphics.scale(scale, scale);
                content.printAll(graphics); graphics.dispose();
                ImageIO.write(image, "png", Path.of("logs/ui-" + suffix + ".png").toFile());
            } catch (Exception exception) { throw new RuntimeException(exception); }
        });
    }

    static void duplicateInstance(Path home, Path jar) throws Exception {
        Process second = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/javaw.exe").toString(),
                "--enable-native-access=ALL-UNNAMED", "-Dcopywords.home=" + home, "-jar", jar.toString()).start();
        var duplicate = new AtomicReference<HWND>();
        try {
            waitFor(() -> {
                User32.INSTANCE.EnumWindows((window, data) -> {
                    var pid = new IntByReference(); User32.INSTANCE.GetWindowThreadProcessId(window, pid);
                    if (Integer.toUnsignedLong(pid.getValue()) == second.pid() && User32.INSTANCE.IsWindowVisible(window)) {
                        char[] title = new char[256]; User32.INSTANCE.GetWindowText(window, title, title.length);
                        if (Native.toString(title).equals("CopyWords")) duplicate.set(window);
                    }
                    return true;
                }, null);
                return duplicate.get() != null;
            }, "duplicate instance did not display prompt");
            User32.INSTANCE.PostMessage(duplicate.get(), 0x0010, new WPARAM(0), new LPARAM(0));
            check(second.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && second.exitValue() == 0,
                    "second javaw process displays duplicate prompt and exits normally");
        } finally { if (second.isAlive()) second.destroyForcibly(); }
    }

    public static void main(String[] args) throws Exception {
        String suffix = args.length == 0 ? "normal" : args[0];
        Path home = Files.createTempDirectory(Path.of("temp").toAbsolutePath(), "ui-smoke-");
        Path config = home.resolve("config.xml");
        Path jar = Path.of("dist/CopyWords.jar").toAbsolutePath();
        String keyName = "CopyWords-UI-SMOKE-" + ProcessHandle.current().pid();
        System.setProperty("copywords.autostartName", keyName);
        var startup = new AutoStart(keyName, AutoStart.command(jar));
        String oldStartup = startup.snapshot();
        Clipboard systemClipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable oldClipboard = systemClipboard.getContents(null);
        FileChannel channel = FileChannel.open(home.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock instance = channel.tryLock();
        boolean success = false;
        try {
            onEdt(() -> {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                    var font = new javax.swing.plaf.FontUIResource("Microsoft YaHei UI", Font.PLAIN, 14);
                    for (Object key : java.util.Collections.list(UIManager.getDefaults().keys()))
                        if (UIManager.get(key) instanceof Font) UIManager.put(key, font);
                    Constructor<?> constructor = CopyWords.class.getDeclaredConstructor(Path.class, FileChannel.class, FileLock.class);
                    constructor.setAccessible(true); app = constructor.newInstance(home, channel, instance);
                    frame = (JFrame) field(app, "frame");
                } catch (Exception exception) { throw new RuntimeException(exception); }
            });
            robot = new Robot(); robot.setAutoDelay(70);
            check(frame.isVisible() && rows().size() == 5, "first launch shows five rows");
            check(frame.getWidth() == 760 && frame.getHeight() == Math.min(1280,
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds().height),
                    "default width unchanged and default height doubled within screen bounds");
            check(!Files.exists(config) && startup.snapshot() == null, "first launch does not save config or enable autostart");
            screenshot(suffix);
            check(findScroll(frame).getViewport().getViewRect().contains(((JPanel) field(rows().getLast(), "panel")).getBounds()),
                    "all five rows fully visible at initial size");
            check(findScroll(frame).getHorizontalScrollBar().getMaximum() <= findScroll(frame).getHorizontalScrollBar().getVisibleAmount(),
                    "no horizontal scrolling at initial size");
            duplicateInstance(home, jar);
            Object first = rows().getFirst();
            systemClipboard.setContents(new StringSelection("capture sentinel"), null);
            Hotkey original = (Hotkey) field(first, "hotkey");
            capture(first, "取消");
            check(original.equals(field(first, "hotkey")), "cancel keeps original shortcut");
            capture(first, "确定");
            check(new Hotkey(Hotkey.CTRL | Hotkey.SHIFT, '4').equals(field(first, "hotkey")), "captured Ctrl+Shift+4 accepted");
            onEdt(() -> {
                try {
                    ((JTextField) field(first, "text")).setText("  测试中文  ");
                    ((JCheckBox) field(app, "autoStart")).setSelected(false);
                    ((JCheckBox) field(app, "startHidden")).setSelected(true);
                    button(frame, "+ 添加").doClick();
                } catch (Exception exception) { throw new RuntimeException(exception); }
            });
            check(rows().size() == 6, "add creates sixth row");
            check(new Hotkey(Hotkey.CAPS, '1').equals(field(rows().getLast(), "hotkey")), "add chooses first unused Caps digit");
            save(config);
            Config persisted = new ConfigStore(config).load();
            check(persisted.entries().size() == 6 && persisted.entries().getFirst().text().equals("  测试中文  "),
                    "save preserves changed shortcut, ordered rows, Chinese and spaces");
            check(persisted.entries().getFirst().hotkey().equals(new Hotkey(6, '4')), "changed binding persisted");
            Object last = rows().getLast();
            onEdt(() -> {
                try { button((Container) field(last, "panel"), "删除").doClick(); }
                catch (Exception exception) { throw new RuntimeException(exception); }
            });
            save(config);
            check(new ConfigStore(config).load().entries().size() == 5, "delete and save remove entry");
            onEdt(() -> frame.setBounds(80, 70, 680, 460));
            Thread.sleep(200);
            screenshot(suffix + "-resized");
            check(!findScroll(frame).getHorizontalScrollBar().isVisible(), "no horizontal scrollbar at minimum width");
            check(frame.getGraphicsConfiguration().getDefaultTransform().getScaleX() > 0,
                    "rendered DPI scale=" + frame.getGraphicsConfiguration().getDefaultTransform().getScaleX());
            Rectangle normalBounds = frame.getBounds();
            byte[] savedTextConfig = Files.readAllBytes(config);
            onEdt(() -> {
                try { ((JTextField) field(first, "text")).setText("unsaved geometry draft"); }
                catch (Exception exception) { throw new RuntimeException(exception); }
                frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
            });
            check(!frame.isVisible(), "close hides window to tray");
            check(WindowState.load(home.resolve("window-state.xml")).bounds().equals(normalBounds),
                    "closing to tray automatically saves window position and size");
            check(java.util.Arrays.equals(savedTextConfig, Files.readAllBytes(config)),
                    "saving window geometry does not save unsaved text draft");
            systemClipboard.setContents(new StringSelection("hidden sentinel"), null);
            robot.keyPress(KeyEvent.VK_CONTROL); robot.keyPress(KeyEvent.VK_SHIFT); robot.keyPress(KeyEvent.VK_4);
            robot.keyRelease(KeyEvent.VK_4); robot.keyRelease(KeyEvent.VK_SHIFT); robot.keyRelease(KeyEvent.VK_CONTROL);
            waitFor(() -> {
                try { return "  测试中文  ".equals(systemClipboard.getData(DataFlavor.stringFlavor)); }
                catch (Exception exception) { return false; }
            }, "hidden-window hotkey did not copy");
            check(!frame.isVisible(), "hotkey copies correct text while app stays hidden in tray");
            TrayIcon tray = (TrayIcon) field(app, "tray");
            check(tray != null, "system tray available");
            JPopupMenu trayMenu = (JPopupMenu) field(app, "trayMenu");
            check(trayMenu.getFont().canDisplayUpTo("打开设置退出") == -1
                    && trayMenu.getComponent(0).getFont().canDisplayUpTo("打开设置") == -1,
                    "tray popup and items have explicit Chinese-capable fonts");
            JDialog menuOwner = (JDialog) field(app, "trayMenuOwner");
            Rectangle screen = frame.getGraphicsConfiguration().getBounds();
            Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(frame.getGraphicsConfiguration());
            Rectangle usable = new Rectangle(screen.x + screenInsets.left, screen.y + screenInsets.top,
                    screen.width - screenInsets.left - screenInsets.right,
                    screen.height - screenInsets.top - screenInsets.bottom);
            onEdt(() -> {
                MouseEvent rightClick = new MouseEvent(frame, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0,
                        usable.x + usable.width - 1, usable.y + usable.height - 1,
                        usable.x + usable.width - 1, usable.y + usable.height - 1, 1, true, MouseEvent.BUTTON3);
                for (MouseListener listener : tray.getMouseListeners()) listener.mouseReleased(rightClick);
            });
            waitFor(trayMenu::isVisible, "tray right-click popup did not open while settings were hidden");
            check(usable.contains(menuOwner.getBounds()), "tray right-click popup stays inside screen near bottom edge");
            Thread.sleep(250);
            robot.keyPress(KeyEvent.VK_ESCAPE); robot.keyRelease(KeyEvent.VK_ESCAPE);
            waitFor(() -> !trayMenu.isVisible() && !menuOwner.isVisible(), "Escape did not dismiss tray popup");
            check(!frame.isVisible(), "Escape dismisses tray popup without reopening settings");
            onEdt(() -> ((JMenuItem) trayMenu.getComponent(0)).doClick());
            waitFor(frame::isVisible, "tray open failed");
            check(frame.isVisible(), "tray menu reopens settings");
            check(frame.getBounds().equals(normalBounds), "tray reopen retains position and size");
            onEdt(() -> {
                try {
                    Method popup = CopyWords.class.getDeclaredMethod("showTrayMenu", int.class, int.class);
                    popup.setAccessible(true); popup.invoke(app, frame.getX() + 20, frame.getY() + 50);
                } catch (Exception exception) { throw new RuntimeException(exception); }
            });
            waitFor(trayMenu::isVisible, "popup did not open before outside-click test");
            Thread.sleep(250);
            robot.mouseMove(frame.getX() + 200, frame.getY() + 15);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            waitFor(() -> !trayMenu.isVisible() && !menuOwner.isVisible(), "outside click did not dismiss tray popup");
            check(frame.isVisible(), "clicking outside tray menu dismisses popup and keeps settings open");
            onEdt(() -> frame.setExtendedState(Frame.MAXIMIZED_BOTH));
            waitFor(() -> (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0, "maximize failed");
            Thread.sleep(400);
            onEdt(() -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
            WindowState maximizedState = WindowState.load(home.resolve("window-state.xml"));
            check(maximizedState.maximized() && maximizedState.bounds().equals(normalBounds),
                    "closing maximized window preserves normal bounds and maximized state");
            ((GlobalHotkeys) field(app, "hotkeys")).close();
            ((ClipboardService) field(app, "clipboard")).close();
            SystemTray.getSystemTray().remove(tray);
            onEdt(() -> {
                try { ((JDialog) field(app, "trayMenuOwner")).dispose(); }
                catch (Exception exception) { throw new RuntimeException(exception); }
            });
            onEdt(frame::dispose);
            onEdt(() -> {
                try {
                    Constructor<?> constructor = CopyWords.class.getDeclaredConstructor(Path.class, FileChannel.class, FileLock.class);
                    constructor.setAccessible(true); app = constructor.newInstance(home, channel, instance);
                    frame = (JFrame) field(app, "frame");
                } catch (Exception exception) { throw new RuntimeException(exception); }
            });
            check(!frame.isVisible(), "saved hidden-start preference is honored on restart");
            check(((JTextField) field(rows().getFirst(), "text")).getText().equals("  测试中文  "), "restart reloads saved text");
            JPopupMenu restartedMenu = (JPopupMenu) field(app, "trayMenu");
            onEdt(() -> ((JMenuItem) restartedMenu.getComponent(0)).doClick());
            waitFor(frame::isVisible, "restart tray open failed");
            waitFor(() -> (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0, "restart did not restore maximized state");
            check(frame.isVisible(), "restart and tray reopen restore maximized window");
            onEdt(() -> frame.setExtendedState(Frame.NORMAL));
            Thread.sleep(400);
            check(frame.getBounds().equals(normalBounds), "restoring from maximized retains saved normal bounds");
            Object reloaded = rows().getFirst();
            onEdt(() -> {
                try { ((JTextField) field(reloaded, "text")).setText("unsaved draft"); }
                catch (Exception exception) { throw new RuntimeException(exception); }
            });
            SwingUtilities.invokeLater(() -> ((JMenuItem) restartedMenu.getComponent(2)).doClick());
            waitFor(() -> dialog("CopyWords") != null, "unsaved exit prompt missing");
            onEdt(() -> button(dialog("CopyWords"), "取消").doClick());
            check(frame.isVisible() && !(Boolean) field(app, "exiting"), "canceling unsaved-exit prompt keeps app running");
            Files.writeString(Path.of("logs/ui-" + suffix + "-profile.txt"), home.toString());
            onEdt(() -> frame.setBounds(100, 90, 700, 480));
            Thread.sleep(200);
            new WindowState(frame.getX(), frame.getY(), frame.getWidth(), frame.getHeight(), false)
                    .save(Path.of("logs/ui-" + suffix + "-expected-window.xml"));
            success = true;
        } finally {
            startup.restore(oldStartup);
            if (oldClipboard != null) systemClipboard.setContents(oldClipboard, null);
            if (!success) {
                if (app != null) {
                    ((GlobalHotkeys) field(app, "hotkeys")).close(); ((ClipboardService) field(app, "clipboard")).close();
                    TrayIcon tray = (TrayIcon) field(app, "tray"); if (tray != null) SystemTray.getSystemTray().remove(tray);
                    onEdt(() -> {
                        try { ((JDialog) field(app, "trayMenuOwner")).dispose(); }
                        catch (Exception exception) { throw new RuntimeException(exception); }
                    });
                    onEdt(frame::dispose);
                }
                if (instance.isValid()) instance.release(); channel.close();
            }
            Files.write(Path.of("logs/ui-" + suffix + "-report.txt"), report, java.nio.charset.StandardCharsets.UTF_8);
        }
        // Exit through the actual tray menu. CopyWords closes registrations, tray and file lock, then exits 0.
        JPopupMenu trayMenu = (JPopupMenu) field(app, "trayMenu");
        SwingUtilities.invokeLater(() -> ((JMenuItem) trayMenu.getComponent(2)).doClick());
        waitFor(() -> dialog("CopyWords") != null, "discard-exit prompt missing");
        onEdt(() -> button(dialog("CopyWords"), "丢弃并退出").doClick());
    }

    static JScrollPane findScroll(Container parent) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JScrollPane pane) return pane;
            if (component instanceof Container nested) { var pane = findScroll(nested); if (pane != null) return pane; }
        }
        return null;
    }
}
