import cn.copywords.*;
import java.nio.file.*;

// Run after UiSmoke exits to verify actual save/discard persistence and released instance lock.
class WindowStateExitCheck {
    public static void main(String[] args) throws Exception {
        String suffix = args.length == 0 ? "normal" : args[0];
        boolean saveOnExit = args.length > 1 && args[1].equals("save");
        Path home = Path.of(Files.readString(Path.of("logs/ui-" + suffix + "-profile.txt")));
        WindowState expected = WindowState.load(Path.of("logs/ui-" + suffix + "-expected-window.xml"));
        if (!expected.equals(WindowState.load(home.resolve("window-state.xml"))))
            throw new AssertionError("discard exit did not persist geometry");
        Config config = new ConfigStore(home.resolve("config.xml")).load();
        if (!(saveOnExit ? "unsaved draft" : "  测试中文  ").equals(config.entries().getFirst().text()))
            throw new AssertionError("exit did not preserve expected text");
        if (config.closeToTray() == saveOnExit) throw new AssertionError("exit did not preserve expected close-to-tray preference");
        if (Files.exists(Path.of("logs/ui-" + suffix + "-exit-failure.txt")))
            throw new AssertionError("exit resource cleanup failed");
        try (var channel = java.nio.channels.FileChannel.open(home.resolve("instance.lock"), StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new AssertionError("instance lock remains held after exit");
        }
        Files.writeString(Path.of("logs/ui-" + suffix + "-report.txt"),
                "PASS actual " + (saveOnExit ? "save" : "discard") + " exit persists expected geometry, text and preference; instance lock available\n",
                java.nio.charset.StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        System.out.println("PASS actual " + (saveOnExit ? "save" : "discard") + " exit persistence and lock release");
    }
}
