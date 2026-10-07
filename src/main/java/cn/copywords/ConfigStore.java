package cn.copywords;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Properties;

public final class ConfigStore {
    private final Path path;
    private boolean upgraded;
    public ConfigStore(Path path) { this.path = path; }
    public boolean exists() { return Files.exists(path); }
    public boolean upgraded() { return upgraded; }

    public Config load() throws IOException {
        var properties = new Properties();
        try (var stream = Files.newInputStream(path)) { properties.loadFromXML(stream); }
        try {
            int count = Integer.parseInt(properties.getProperty("count"));
            if (count < 0) throw new IllegalArgumentException("条目数量无效");
            var entries = new ArrayList<Entry>();
            for (int i = 0; i < count; i++) {
                String prefix = "entry." + i + ".";
                String modifiers = properties.getProperty(prefix + "modifiers");
                Hotkey hotkey = modifiers == null ? null : new Hotkey(Integer.parseInt(modifiers),
                        Integer.parseInt(properties.getProperty(prefix + "key")));
                String id = properties.getProperty(prefix + "id");
                String text = properties.getProperty(prefix + "text");
                if (id == null || text == null) throw new IllegalArgumentException("条目不完整");
                entries.add(new Entry(id, text, hotkey));
            }
            var config = new Config(entries, Boolean.parseBoolean(properties.getProperty("autoStart", "true")),
                    Boolean.parseBoolean(properties.getProperty("startHidden", "true")),
                    Boolean.parseBoolean(properties.getProperty("closeToTray", "false")));
            config.validateLoaded();
            Config current = properties.containsKey("capsDefaultsVersion") ? config : config.upgradeLegacyDefaults();
            upgraded = !current.equals(config);
            return current;
        } catch (RuntimeException exception) { throw new IOException("配置文件内容无效", exception); }
    }

    public void save(Config config) throws IOException {
        config.validate();
        var properties = new Properties();
        properties.setProperty("capsDefaultsVersion", "2");
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
