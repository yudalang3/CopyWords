import cn.copywords.*;
import java.nio.file.*;

// Run after UiSmoke exits to verify automatic persistence and released instance lock.
class WindowStateExitCheck {
    public static void main(String[] args) throws Exception {
        String suffix = args.length == 0 ? "normal" : args[0];
        Path home = Path.of(Files.readString(Path.of("logs/ui-" + suffix + "-profile.txt")));
        WindowState expected = WindowState.load(Path.of("logs/ui-" + suffix + "-expected-window.xml"));
        if (!expected.equals(WindowState.load(home.resolve("window-state.xml"))))
            throw new AssertionError("automatic exit did not persist geometry");
        Config config = new ConfigStore(home.resolve("config.xml")).load();
        if (!"退出前最后修改".equals(config.entries().getFirst().text()))
            throw new AssertionError("exit did not preserve expected text");
        if (config.closeToTray()) throw new AssertionError("exit did not preserve expected close-to-tray preference");
        if (Files.exists(Path.of("logs/ui-" + suffix + "-exit-failure.txt")))
            throw new AssertionError("exit resource cleanup failed");
        try (var channel = java.nio.channels.FileChannel.open(home.resolve("instance.lock"), StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new AssertionError("instance lock remains held after exit");
        }
        Files.writeString(Path.of("logs/ui-" + suffix + "-report.txt"),
                "PASS automatic exit persists expected geometry, text and preference; instance lock available\n",
                java.nio.charset.StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        System.out.println("PASS automatic exit persistence and lock release");
    }
}
