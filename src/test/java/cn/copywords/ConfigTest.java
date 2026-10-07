package cn.copywords;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {
    @TempDir Path directory;

    @Test void initialEntriesAndDefaultAllocation() {
        Config config = Config.defaults();
        assertEquals(5, config.entries().size());
        assertEquals("人生价值", config.entries().get(1).text());
        assertEquals(new Hotkey(Hotkey.CAPS, '4'), Hotkey.nextDefault(config.entries()));
        var entries = new ArrayList<Entry>();
        for (char digit : "1234567890".toCharArray()) entries.add(Entry.create("", new Hotkey(Hotkey.CAPS, digit)));
        assertNull(Hotkey.nextDefault(entries));
        entries.remove(4);
        assertEquals(new Hotkey(Hotkey.CAPS, '5'), Hotkey.nextDefault(entries));
    }

    @Test void shortcutConversionAndValidation() {
        var event = new KeyEvent(new Canvas(), KeyEvent.KEY_PRESSED, 0,
                KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK, KeyEvent.VK_2, '2');
        assertEquals(new Hotkey(Hotkey.CTRL | Hotkey.SHIFT, '2'), Hotkey.from(event));
        assertEquals("Ctrl + Shift + 2", Hotkey.from(event).toString());
        assertEquals(0x7A, new Hotkey(Hotkey.ALT, KeyEvent.VK_F11).key());
        assertEquals("左 Alt + F11", new Hotkey(Hotkey.ALT, KeyEvent.VK_F11).toString());
        var alt = new KeyEvent(new Canvas(), KeyEvent.KEY_PRESSED, 0,
                KeyEvent.ALT_DOWN_MASK, KeyEvent.VK_7, '7');
        assertEquals(new Hotkey(Hotkey.ALT, '7'), Hotkey.from(alt, false));
        assertEquals(Hotkey.LEFT_ALT_ADVICE,
                assertThrows(IllegalArgumentException.class, () -> Hotkey.from(alt, true)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> new Hotkey(0, 'A'));
        assertEquals("Caps + A", new Hotkey(8, 'A').toString());
        assertThrows(IllegalArgumentException.class, () -> new Hotkey(16, 'A'));
        assertThrows(IllegalArgumentException.class, () -> new Hotkey(Hotkey.ALT, KeyEvent.VK_F12));
        assertThrows(IllegalArgumentException.class, () -> new Hotkey(Hotkey.ALT, KeyEvent.VK_ENTER));
    }

    @Test void duplicateAndMultilineRowsAreRejected() {
        var key = new Hotkey(Hotkey.ALT, '1');
        var duplicate = new Config(List.of(Entry.create("a", key), Entry.create("b", key)), false, false);
        assertTrue(assertThrows(IllegalArgumentException.class, duplicate::validate).getMessage().contains("第 2 行"));
        var multiline = new Config(List.of(Entry.create("第一行\n第二行", key)), false, false);
        assertThrows(IllegalArgumentException.class, multiline::validate);
        assertFalse(Entry.create("", key).active());
        assertFalse(Entry.create("内容", null).active());
        assertTrue(Entry.create("  ", key).active());
    }

    @Test void orderedUnicodeAndSpacesRoundTrip() throws Exception {
        var original = new Config(List.of(Entry.create("  人生价值 & <AI4S>  ", new Hotkey(3, 'Z')),
                Entry.create("", null), Entry.create("email@example.com", null)), false, true);
        var store = new ConfigStore(directory.resolve("config.xml"));
        store.save(original);
        assertEquals(original, store.load());
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void failedSavePreservesPreviousFile() throws Exception {
        var store = new ConfigStore(directory.resolve("config.xml"));
        var previous = Config.defaults(); store.save(previous);
        byte[] original = Files.readAllBytes(directory.resolve("config.xml"));
        var key = new Hotkey(Hotkey.ALT, '1');
        var invalid = new Config(List.of(Entry.create("x", key), Entry.create("y", key)), false, false);
        assertThrows(IllegalArgumentException.class, () -> store.save(invalid));
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("config.xml")));
        assertEquals(previous, store.load());
    }

    @Test void corruptConfigIsReportedWithoutOverwrite() throws Exception {
        Path path = directory.resolve("config.xml");
        Files.writeString(path, "broken xml");
        assertThrows(java.io.IOException.class, () -> new ConfigStore(path).load());
        assertEquals("broken xml", Files.readString(path));
    }

    @Test void reservedPasteShortcutIsRejectedOnSaveButLegacyFileIsPreserved() throws Exception {
        var path = directory.resolve("config.xml");
        var store = new ConfigStore(path);
        var config = new Config(List.of(Entry.create("preserved", new Hotkey(Hotkey.CTRL, 'V'))), false, false);
        assertTrue(assertThrows(IllegalArgumentException.class, config::validate).getMessage().contains("Ctrl + V"));
        var properties = new Properties();
        properties.setProperty("count", "1"); properties.setProperty("capsDefaultsVersion", "2");
        properties.setProperty("autoStart", "false"); properties.setProperty("startHidden", "false");
        properties.setProperty("entry.0.id", config.entries().getFirst().id());
        properties.setProperty("entry.0.text", "preserved");
        properties.setProperty("entry.0.modifiers", "2"); properties.setProperty("entry.0.key", "86");
        try (var output = Files.newOutputStream(path)) { properties.storeToXML(output, "legacy", "UTF-8"); }
        byte[] original = Files.readAllBytes(path);
        assertEquals(config, store.load());
        assertArrayEquals(original, Files.readAllBytes(path));
        assertThrows(IllegalArgumentException.class, () -> store.save(config));
        assertArrayEquals(original, Files.readAllBytes(path));
        new Config(List.of(Entry.create("allowed", new Hotkey(Hotkey.CAPS | Hotkey.CTRL, 'V'))), false, false).validate();
    }

    @Test void legacyDefaultsUpgradeWithoutChangingTextOrCustomBindings() throws Exception {
        var custom = new Hotkey(Hotkey.CTRL | Hotkey.SHIFT | Hotkey.ALT, '1');
        var legacy = new Config(List.of(Entry.create("my first text", new Hotkey(Hotkey.ALT, '1')),
                Entry.create("中文", new Hotkey(6, '2')), Entry.create("mail", new Hotkey(Hotkey.ALT, '3')),
                Entry.create("custom", custom), Entry.create("next", new Hotkey(Hotkey.ALT, '2'))), true, false);
        var path = directory.resolve("config.xml");
        new ConfigStore(path).save(legacy);
        var properties = new Properties();
        try (var input = Files.newInputStream(path)) { properties.loadFromXML(input); }
        properties.remove("capsDefaultsVersion");
        try (var output = Files.newOutputStream(path)) { properties.storeToXML(output, "legacy", "UTF-8"); }
        byte[] original = Files.readAllBytes(path);
        var store = new ConfigStore(path); var upgraded = store.load();
        assertTrue(store.upgraded());
        assertEquals(new Hotkey(Hotkey.CAPS, '1'), upgraded.entries().get(0).hotkey());
        assertEquals(new Hotkey(Hotkey.CAPS, '2'), upgraded.entries().get(1).hotkey());
        assertEquals(new Hotkey(Hotkey.CAPS, '3'), upgraded.entries().get(2).hotkey());
        assertEquals(custom, upgraded.entries().get(3).hotkey());
        assertEquals(new Hotkey(Hotkey.CAPS, '4'), upgraded.entries().get(4).hotkey());
        assertEquals(legacy.entries().stream().map(Entry::text).toList(), upgraded.entries().stream().map(Entry::text).toList());
        assertArrayEquals(original, Files.readAllBytes(path));
        store.save(upgraded);
        assertEquals(upgraded, new ConfigStore(path).load());
    }

    @Test void conflictMessageExplainsRecoveryInsteadOfOnlyErrorCode() {
        String advice = GlobalHotkeys.registrationAdvice(new Hotkey(Hotkey.ALT, '1'), 1409);
        assertTrue(advice.contains("已被其他程序占用"));
        assertTrue(advice.contains("Caps + 1"));
        assertTrue(advice.contains("保存"));
        assertFalse(advice.contains("1409"));
    }
}
