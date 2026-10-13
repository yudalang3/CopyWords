import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

// Verifies archive safety against a real Java instance lock using a disposable profile.
class ArchiveConfigSmoke {
    static String archive(Path home, boolean success) throws Exception {
        Path output = home.resolve(success ? "archive-output.txt" : "busy-output.txt");
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", Path.of("scripts/archive-old-config.ps1").toAbsolutePath().toString(),
                "-ProfileHome", home.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("archive script timed out");
        }
        if ((process.exitValue() == 0) != success) throw new AssertionError(Files.readString(output));
        return Files.readString(output);
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory(Path.of("temp").toAbsolutePath(), "archive-smoke-");
        Path config = home.resolve("config.xml");
        Path state = home.resolve("window-state.xml");
        byte[] original = "旧配置：  中文😀  ".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(config, original);
        Files.writeString(state, "window geometry must remain unchanged");
        try (var channel = FileChannel.open(home.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            archive(home, false);
            if (!Arrays.equals(original, Files.readAllBytes(config))) throw new AssertionError("busy profile was modified");
        }
        String result = archive(home, true);
        String marker = "Archived configuration: ";
        Path archived = Path.of(result.substring(result.indexOf(marker) + marker.length()).strip());
        if (Files.exists(config) || !Arrays.equals(original, Files.readAllBytes(archived)))
            throw new AssertionError("archive did not preserve exact contents");
        if (Files.readAllLines(archived.resolveSibling("archive.txt")).size() != 3)
            throw new AssertionError("archive metadata is incomplete");
        if (!"window geometry must remain unchanged".equals(Files.readString(state)))
            throw new AssertionError("window state was modified");
        String report = "PASS busy Java instance refuses archive; copied bytes verified; original removed after verification; window state preserved\n";
        Files.writeString(Path.of("logs/archive-smoke-report.txt"), report);
        System.out.print(report);
    }
}
