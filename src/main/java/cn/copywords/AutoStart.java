package cn.copywords;

import com.sun.jna.platform.win32.*;
import java.nio.file.Path;

public final class AutoStart {
    private static final String KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private final String name;
    private final String command;

    public AutoStart(Path jar) { this("CopyWords", command(jar)); }
    public AutoStart(String name, String command) { this.name = name; this.command = command; }
    public static String command(Path jar) {
        Path javaw = Path.of(System.getProperty("java.home"), "bin", "javaw.exe");
        return "\"" + javaw + "\" --enable-native-access=ALL-UNNAMED -jar \"" + jar.toAbsolutePath() + "\"";
    }
    public String snapshot() {
        return Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, name)
                ? Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, KEY, name) : null;
    }
    public void restore(String previous) {
        if (previous == null) {
            if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, name))
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, KEY, name);
        } else {
            Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, KEY);
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, name, previous);
        }
    }
    public void set(boolean enabled) {
        if (enabled && command.length() > 260) throw new IllegalArgumentException("启动命令超过 Windows 自启动长度限制，请将应用放到较短路径");
        restore(enabled ? command : null);
    }
}
