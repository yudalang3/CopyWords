import cn.copywords.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.lang.reflect.*;
import java.nio.channels.*;
import java.nio.file.*;

// Verify clean window exit by default and explicit tray exit while close-to-tray is enabled.
class CloseExitSmoke {
    static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true); return field.get(object);
    }

    public static void main(String[] args) throws Exception {
        boolean trayExit = args.length > 0 && args[0].equals("tray");
        String mode = trayExit ? "tray" : "default";
        Path report = Path.of("logs/close-exit-" + mode + ".txt");
        Path home = Files.createTempDirectory(Path.of("temp").toAbsolutePath(), "close-exit-");
        if (trayExit) new ConfigStore(home.resolve("config.xml")).save(
                new Config(Config.defaults().entries(), false, false, true));
        System.setProperty("copywords.autostartName", "CopyWords-CLOSE-SMOKE-" + ProcessHandle.current().pid());
        FileChannel channel = FileChannel.open(home.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = channel.tryLock();
        Object[] app = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                Constructor<?> constructor = CopyWords.class.getDeclaredConstructor(Path.class, FileChannel.class, FileLock.class);
                constructor.setAccessible(true); app[0] = constructor.newInstance(home, channel, lock);
            } catch (Exception exception) { throw new RuntimeException(exception); }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (!(Boolean) field(app[0], "exiting")) throw new AssertionError("did not use application exit flow");
                if (lock.isValid() || channel.isOpen()) throw new AssertionError("instance lock was not released");
                if (((Thread) field(field(app[0], "hotkeys"), "thread")).isAlive()) throw new AssertionError("hotkey thread remains alive");
                if (java.util.Arrays.asList(SystemTray.getSystemTray().getTrayIcons()).contains(field(app[0], "tray")))
                    throw new AssertionError("tray icon remains registered");
                if (!Files.exists(home.resolve("window-state.xml"))) throw new AssertionError("window state was not saved");
                if (!trayExit && Files.exists(home.resolve("config.xml"))) throw new AssertionError("clean exit saved config implicitly");
                Files.writeString(report, "PASS " + mode + " exit without unsaved prompt; geometry saved and native resources released\n");
            } catch (Exception | AssertionError exception) {
                try { Files.writeString(report, "FAIL " + exception + "\n"); }
                catch (Exception ignored) { }
            }
        }));
        SwingUtilities.invokeLater(() -> {
            try {
                if (trayExit) ((JMenuItem) ((JPopupMenu) field(app[0], "trayMenu")).getComponent(2)).doClick();
                else ((JFrame) field(app[0], "frame")).dispatchEvent(
                        new WindowEvent((JFrame) field(app[0], "frame"), WindowEvent.WINDOW_CLOSING));
            } catch (Exception exception) { throw new RuntimeException(exception); }
        });
        Thread.sleep(8000);
        Files.writeString(report, "FAIL exit did not finish within eight seconds\n");
        Runtime.getRuntime().halt(1);
    }
}
