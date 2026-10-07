import cn.copywords.*;
import javax.swing.*;
import java.awt.*;
import java.lang.reflect.*;
import java.nio.channels.*;
import java.nio.file.*;

// Displays the production tray popup at a visible test-window anchor.
class TrayMenuSmoke {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory(Path.of("temp").toAbsolutePath(), "tray-menu-");
        Config defaults = Config.defaults();
        new ConfigStore(home.resolve("config.xml")).save(new Config(defaults.entries(), false, false, false));
        var channel = FileChannel.open(home.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        var lock = channel.tryLock();
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                var font = new javax.swing.plaf.FontUIResource("Microsoft YaHei UI", Font.PLAIN, 14);
                for (Object key : java.util.Collections.list(UIManager.getDefaults().keys()))
                    if (UIManager.get(key) instanceof Font) UIManager.put(key, font);
                Constructor<?> constructor = CopyWords.class.getDeclaredConstructor(Path.class, FileChannel.class, FileLock.class);
                constructor.setAccessible(true);
                Object app = constructor.newInstance(home, channel, lock);
                Field frameField = CopyWords.class.getDeclaredField("frame"); frameField.setAccessible(true);
                JFrame frame = (JFrame) frameField.get(app);
                frame.setTitle("CopyWords — 托盘菜单显示验证");
                frame.setBounds(160, 100, 760, 700);
                JButton show = new JButton("显示实际托盘菜单");
                show.addActionListener(event -> {
                    try {
                        Method popup = CopyWords.class.getDeclaredMethod("showTrayMenu", int.class, int.class);
                        popup.setAccessible(true);
                        popup.invoke(app, frame.getX() + 180, frame.getY() + 100);
                    } catch (Exception exception) { throw new RuntimeException(exception); }
                });
                JMenuBar bar = new JMenuBar(); bar.add(show); frame.setJMenuBar(bar);
                frame.validate();
            } catch (Exception exception) { throw new RuntimeException(exception); }
        });
    }
}
