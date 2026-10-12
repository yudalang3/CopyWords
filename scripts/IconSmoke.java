import cn.copywords.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.lang.reflect.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.List;

// Checks actual application icons without sending input or using personal settings.
class IconSmoke {
    static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    public static void main(String[] args) throws Exception {
        String suffix = args.length == 0 ? "100percent" : args[0];
        Path home = Files.createTempDirectory(Path.of("temp").toAbsolutePath(), "icon-smoke-");
        new ConfigStore(home.resolve("config.xml")).save(new Config(List.of(), false, false, false));
        System.setProperty("copywords.autostartName", "CopyWords-ICON-SMOKE-" + ProcessHandle.current().pid());
        Object[] application = new Object[1];
        try (FileChannel channel = FileChannel.open(home.resolve("instance.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            try {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                        var font = new javax.swing.plaf.FontUIResource("Microsoft YaHei UI", Font.PLAIN, 14);
                        for (Object key : java.util.Collections.list(UIManager.getDefaults().keys()))
                            if (UIManager.get(key) instanceof Font) UIManager.put(key, font);
                        Constructor<?> constructor = CopyWords.class.getDeclaredConstructor(
                                Path.class, FileChannel.class, FileLock.class);
                        constructor.setAccessible(true);
                        application[0] = constructor.newInstance(home, channel, lock);
                    } catch (Exception exception) { throw new RuntimeException(exception); }
                });
                Object app = application[0];
                JFrame frame = (JFrame) field(app, "frame");
                int[] sizes = {16, 24, 32, 48, 64, 128, 256};
                if (frame.getIconImages().size() != sizes.length)
                    throw new AssertionError("Window icon sizes missing");
                for (int index = 0; index < sizes.length; index++) {
                    BufferedImage image = (BufferedImage) frame.getIconImages().get(index);
                    if (image.getWidth() != sizes[index] || image.getHeight() != sizes[index])
                        throw new AssertionError("Incorrect icon dimensions");
                    if ((image.getRGB(0, 0) >>> 24) != 0)
                        throw new AssertionError("Icon corner is not transparent");
                }
                TrayIcon tray = (TrayIcon) field(app, "tray");
                if (tray == null || !List.of(SystemTray.getSystemTray().getTrayIcons()).contains(tray))
                    throw new AssertionError("Tray icon not installed");
                MultiResolutionImage trayImage = (MultiResolutionImage) tray.getImage();
                for (int size : sizes) {
                    Image variant = trayImage.getResolutionVariant(size, size);
                    if (variant.getWidth(null) != size || variant.getHeight(null) != size)
                        throw new AssertionError("Tray resolution variant missing");
                }
                Robot robot = new Robot();
                SwingUtilities.invokeAndWait(() -> {
                    frame.setAlwaysOnTop(true);
                    frame.toFront();
                });
                robot.waitForIdle();
                Thread.sleep(800);
                var captures = robot.createMultiResolutionScreenCapture(frame.getBounds()).getResolutionVariants();
                ImageIO.write((BufferedImage) captures.getLast(), "png",
                        Path.of("logs/icon-window-" + suffix + ".png").toFile());
                Rectangle screen = frame.getGraphicsConfiguration().getBounds();
                Rectangle taskbar = new Rectangle(screen.x, screen.y + screen.height - 100, screen.width, 100);
                var taskbarCaptures = robot.createMultiResolutionScreenCapture(taskbar).getResolutionVariants();
                ImageIO.write((BufferedImage) taskbarCaptures.getLast(), "png",
                        Path.of("logs/icon-taskbar-" + suffix + ".png").toFile());
                Files.writeString(Path.of("logs/icon-" + suffix + "-report.txt"),
                        "PASS actual window has all seven transparent PNG sizes\n"
                        + "PASS installed tray uses all seven resolution variants\n"
                        + "Rendered Java UI scale: " + frame.getGraphicsConfiguration().getDefaultTransform().getScaleX() + "\n"
                        + "Isolated profile: " + home + "\n");
                System.out.println("PASS window and tray icons: " + suffix);
            } finally {
                Object app = application[0];
                if (app != null) {
                    ((GlobalHotkeys) field(app, "hotkeys")).close();
                    ((ClipboardService) field(app, "clipboard")).close();
                    TrayIcon tray = (TrayIcon) field(app, "tray");
                    if (tray != null) SystemTray.getSystemTray().remove(tray);
                    SwingUtilities.invokeAndWait(() -> {
                        try {
                            ((JDialog) field(app, "trayMenuOwner")).dispose();
                            ((JFrame) field(app, "frame")).dispose();
                        } catch (Exception exception) { throw new RuntimeException(exception); }
                    });
                }
            }
        }
    }
}
