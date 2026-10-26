package cn.copywords;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Properties;

public final class ConfigStore {
    private final Path path;
    public ConfigStore(Path path) { this.path = path; }
    public boolean exists() { return Files.exists(path); }

    public Config load() throws IOException {
        var properties = new Properties();
        try (var stream = Files.newInputStream(path)) { properties.loadFromXML(stream); }
        try {
            int count = Integer.parseInt(properties.getProperty("count"));
            if (count < 0) throw new IllegalArgumentException("条目数量无效");
            var entries = new ArrayList<Entry>();
            boolean droppedWriting = false;
            for (int i = 0; i < count; i++) {
                String prefix = "entry." + i + ".";
                String keyText = properties.getProperty(prefix + "key");
                if (keyText != null && MarkdownShortcuts.reservedKey(Integer.parseInt(keyText))) {
                    droppedWriting = true; continue;
                }
                String modifiers = properties.getProperty(prefix + "modifiers");
                Hotkey hotkey = modifiers == null ? null : new Hotkey(Integer.parseInt(modifiers),
                        Integer.parseInt(keyText));
                String id = properties.getProperty(prefix + "id");
                String text = properties.getProperty(prefix + "text");
                if (id == null || text == null) throw new IllegalArgumentException("条目不完整");
                entries.add(new Entry(id, text, hotkey));
            }
            var effective = entries.isEmpty() && droppedWriting ? Config.defaults().entries() : entries;
            var config = new Config(effective, readBoolean(properties, "autoStart"),
                    readBoolean(properties, "startHidden"), readBoolean(properties, "closeToTray"));
            config.validate();
            return config;
        } catch (RuntimeException exception) { throw new IOException("配置文件内容无效", exception); }
    }

    private static boolean readBoolean(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (!"true".equals(value) && !"false".equals(value))
            throw new IllegalArgumentException("配置选项缺失或无效：" + name);
        return Boolean.parseBoolean(value);
    }

    public void save(Config config) throws IOException {
        config.validate();
        var properties = new Properties();
        properties.setProperty("count", Integer.toString(config.entries().size()));
        properties.setProperty("autoStart", Boolean.toString(config.autoStart()));
        properties.setProperty("startHidden", Boolean.toString(config.startHidden()));
        properties.setProperty("closeToTray", Boolean.toString(config.closeToTray()));
        for (int i = 0; i < config.entries().size(); i++) {
            var entry = config.entries().get(i);
            String prefix = "entry." + i + ".";
            properties.setProperty(prefix + "id", entry.id());
            properties.setProperty(prefix + "text", entry.text());
            if (entry.hotkey() != null) {
                properties.setProperty(prefix + "modifiers", Integer.toString(entry.hotkey().modifiers()));
                properties.setProperty(prefix + "key", Integer.toString(entry.hotkey().key()));
            }
        }
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "config-", ".tmp");
        try {
            try (var stream = Files.newOutputStream(temporary)) { properties.storeToXML(stream, "CopyWords", "UTF-8"); }
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
