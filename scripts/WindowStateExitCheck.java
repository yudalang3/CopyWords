import cn.copywords.*;
import java.nio.file.*;

// Run after UiSmoke exits to verify actual discard-and-exit persistence.
class WindowStateExitCheck {
    public static void main(String[] args) throws Exception {
        String suffix = args.length == 0 ? "normal" : args[0];
        Path home = Path.of(Files.readString(Path.of("logs/ui-" + suffix + "-profile.txt")));
        WindowState expected = WindowState.load(Path.of("logs/ui-" + suffix + "-expected-window.xml"));
        if (!expected.equals(WindowState.load(home.resolve("window-state.xml"))))
            throw new AssertionError("discard exit did not persist geometry");
        if (!"  测试中文  ".equals(new ConfigStore(home.resolve("config.xml")).load().entries().getFirst().text()))
            throw new AssertionError("discard exit saved unsaved text");
        Files.writeString(Path.of("logs/ui-" + suffix + "-report.txt"),
                "PASS actual discard exit saves geometry and keeps previously saved text\n",
                java.nio.charset.StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        System.out.println("PASS actual discard exit saves geometry and keeps previously saved text");
    }
}
