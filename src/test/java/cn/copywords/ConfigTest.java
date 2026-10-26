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
        assertFalse(config.closeToTray());
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
        var minus = new KeyEvent(new Canvas(), KeyEvent.KEY_PRESSED, 0, KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_MINUS, '-');
        assertThrows(IllegalArgumentException.class, () -> Hotkey.from(minus));
        assertFalse(Hotkey.supported(MarkdownShortcuts.MINUS));
        assertFalse(Hotkey.supported(MarkdownShortcuts.EQUALS));
        assertFalse(Hotkey.supported(MarkdownShortcuts.BACKSLASH));
    }

    @Test void duplicateAndMultilineRowsAreRejected() {
        var key = new Hotkey(Hotkey.ALT, '1');
        var duplicate = new Config(List.of(Entry.create("a", key), Entry.create("b", key)), false, false, false);
        assertTrue(assertThrows(IllegalArgumentException.class, duplicate::validate).getMessage().contains("第 2 行"));
        var multiline = new Config(List.of(Entry.create("第一行\n第二行", key)), false, false, false);
        assertThrows(IllegalArgumentException.class, multiline::validate);
        assertFalse(Entry.create("", key).active());
        assertFalse(Entry.create("内容", null).active());
        assertTrue(Entry.create("  ", key).active());
    }

    @Test void orderedUnicodeAndSpacesRoundTrip() throws Exception {
        var original = new Config(List.of(Entry.create("  人生价值 & <AI4S>  ", new Hotkey(3, 'Z')),
                Entry.create("", null), Entry.create("email@example.com", null)), false, true, false);
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
        var invalid = new Config(List.of(Entry.create("x", key), Entry.create("y", key)), false, false, false);
        assertThrows(IllegalArgumentException.class, () -> store.save(invalid));
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("config.xml")));
        assertEquals(previous, store.load());
    }

    @Test void confirmedEmptyTextKeepsShortcutButDisablesEntryAfterReload() throws Exception {
        Hotkey key = new Hotkey(Hotkey.CAPS, '1');
        Entry entry = Entry.create("原内容", key);
        var store = new ConfigStore(directory.resolve("config.xml"));
        store.save(new Config(List.of(entry), false, false, false));
        store.save(new Config(List.of(new Entry(entry.id(), "", key)), false, false, false));
        Entry reloaded = store.load().entries().getFirst();
        assertEquals(entry.id(), reloaded.id());
        assertEquals(key, reloaded.hotkey());
        assertFalse(reloaded.active());
    }

    @Test void closeToTrayPreferenceRoundTripsBothStates() throws Exception {
        var store = new ConfigStore(directory.resolve("config.xml"));
        for (boolean closeToTray : new boolean[] {true, false}) {
            var config = new Config(Config.defaults().entries(), false, true, closeToTray);
            store.save(config);
            assertEquals(config, store.load());
        }
    }

    @Test void missingOrInvalidPreferencesAreRejectedWithoutRewritingFile() throws Exception {
        Path path = directory.resolve("config.xml");
        var store = new ConfigStore(path);
        for (String name : List.of("autoStart", "startHidden", "closeToTray")) {
            for (String value : Arrays.asList(null, "invalid")) {
                store.save(Config.defaults());
                var properties = new Properties();
                try (var input = Files.newInputStream(path)) { properties.loadFromXML(input); }
                if (value == null) properties.remove(name); else properties.setProperty(name, value);
                try (var output = Files.newOutputStream(path)) { properties.storeToXML(output, "invalid", "UTF-8"); }
                byte[] original = Files.readAllBytes(path);
                var exception = assertThrows(java.io.IOException.class, store::load);
                assertTrue(exception.getCause().getMessage().contains(name));
                assertArrayEquals(original, Files.readAllBytes(path));
            }
        }
    }

    @Test void corruptConfigIsReportedWithoutOverwrite() throws Exception {
        Path path = directory.resolve("config.xml");
        Files.writeString(path, "broken xml");
        assertThrows(java.io.IOException.class, () -> new ConfigStore(path).load());
        assertEquals("broken xml", Files.readString(path));
    }

    @Test void reservedPasteShortcutIsRejectedOnLoadAndSaveWithoutOverwritingFile() throws Exception {
        var path = directory.resolve("config.xml");
        var store = new ConfigStore(path);
        var config = new Config(List.of(Entry.create("preserved", new Hotkey(Hotkey.CTRL, 'V'))), false, false, false);
        assertTrue(assertThrows(IllegalArgumentException.class, config::validate).getMessage().contains("Ctrl + V"));
        var properties = new Properties();
        properties.setProperty("count", "1");
        properties.setProperty("autoStart", "false"); properties.setProperty("startHidden", "false");
        properties.setProperty("closeToTray", "false");
        properties.setProperty("entry.0.id", config.entries().getFirst().id());
        properties.setProperty("entry.0.text", "preserved");
        properties.setProperty("entry.0.modifiers", "2"); properties.setProperty("entry.0.key", "86");
        try (var output = Files.newOutputStream(path)) { properties.storeToXML(output, "invalid", "UTF-8"); }
        byte[] original = Files.readAllBytes(path);
        var exception = assertThrows(java.io.IOException.class, store::load);
        assertTrue(exception.getCause().getMessage().contains("Ctrl + V"));
        assertArrayEquals(original, Files.readAllBytes(path));
        assertThrows(IllegalArgumentException.class, () -> store.save(config));
        assertArrayEquals(original, Files.readAllBytes(path));
        new Config(List.of(Entry.create("allowed", new Hotkey(Hotkey.CAPS | Hotkey.CTRL, 'V'))), false, false, false).validate();
    }

    @Test void savedBindingsAreLoadedExactlyWithoutAutomaticConversion() throws Exception {
        var custom = new Hotkey(Hotkey.CTRL | Hotkey.SHIFT | Hotkey.ALT, '1');
        var original = new Config(List.of(Entry.create("my first text", new Hotkey(Hotkey.ALT, '1')),
                Entry.create("中文", new Hotkey(6, '2')), Entry.create("mail", new Hotkey(Hotkey.ALT, '3')),
                Entry.create("custom", custom), Entry.create("next", new Hotkey(Hotkey.ALT, '2'))), true, false, true);
        var path = directory.resolve("config.xml");
        var store = new ConfigStore(path);
        store.save(original);
        byte[] contents = Files.readAllBytes(path);
        assertEquals(original, store.load());
        assertArrayEquals(contents, Files.readAllBytes(path));
    }

    @Test void conflictMessageExplainsRecoveryInsteadOfOnlyErrorCode() {
        String advice = GlobalHotkeys.registrationAdvice(new Hotkey(Hotkey.ALT, '1'), 1409);
        assertTrue(advice.contains("已被其他程序占用"));
        assertTrue(advice.contains("Caps + 1"));
        assertTrue(advice.contains("自动保存"));
        assertTrue(advice.contains("重试"));
        assertFalse(advice.contains("点击“保存”"));
        assertFalse(advice.contains("1409"));
    }

    @Test void writingShortcutsStayOutOfPhraseEntries() {
        assertEquals(new MarkdownShortcuts.Action("``", 1), MarkdownShortcuts.match(MarkdownShortcuts.MINUS));
        assertEquals(new MarkdownShortcuts.Action("****", 2), MarkdownShortcuts.match(MarkdownShortcuts.EQUALS));
        assertEquals(new MarkdownShortcuts.Action("**", 1), MarkdownShortcuts.match(MarkdownShortcuts.BACKSLASH));
        assertNull(MarkdownShortcuts.match('1'));
    }

    @Test void savedWritingRowsAreDroppedWithoutRewritingFile() throws Exception {
        Path path = directory.resolve("config.xml");
        var store = new ConfigStore(path);
        Entry kept = Entry.create("AI4S", new Hotkey(Hotkey.CAPS, '1'));
        store.save(new Config(List.of(kept), false, true, false));
        var properties = new Properties();
        try (var input = Files.newInputStream(path)) { properties.loadFromXML(input); }
        properties.setProperty("count", "2");
        properties.setProperty("markdownSnippetsSeeded", "true");
        properties.setProperty("entry.1.id", "writing-backtick");
        properties.setProperty("entry.1.text", "``");
        properties.setProperty("entry.1.caret", "1");
        properties.setProperty("entry.1.modifiers", "8");
        properties.setProperty("entry.1.key", Integer.toString(MarkdownShortcuts.MINUS));
        try (var output = Files.newOutputStream(path)) { properties.storeToXML(output, "mixed", "UTF-8"); }
        byte[] original = Files.readAllBytes(path);
        Config loaded = store.load();
        assertEquals(List.of(kept), loaded.entries());
        assertArrayEquals(original, Files.readAllBytes(path));
    }

    @Test void configWithOnlyWritingRowsFallsBackToDefaultEntriesWithoutRewritingFile() throws Exception {
        Path path = directory.resolve("config.xml");
        var properties = new Properties();
        properties.setProperty("count", "1");
        properties.setProperty("autoStart", "false");
        properties.setProperty("startHidden", "true");
        properties.setProperty("closeToTray", "false");
        properties.setProperty("entry.0.id", "writing-backtick");
        properties.setProperty("entry.0.text", "``");
        properties.setProperty("entry.0.modifiers", "8");
        properties.setProperty("entry.0.key", Integer.toString(MarkdownShortcuts.MINUS));
        try (var output = Files.newOutputStream(path)) { properties.storeToXML(output, "legacy", "UTF-8"); }
        byte[] original = Files.readAllBytes(path);
        Config loaded = new ConfigStore(path).load();
        var defaults = Config.defaults().entries();
        assertEquals(defaults.size(), loaded.entries().size());
        for (int i = 0; i < defaults.size(); i++) {
            assertEquals(defaults.get(i).text(), loaded.entries().get(i).text());
            assertEquals(defaults.get(i).hotkey(), loaded.entries().get(i).hotkey());
        }
        assertFalse(loaded.autoStart());
        assertTrue(loaded.startHidden());
        assertFalse(loaded.closeToTray());
        assertArrayEquals(original, Files.readAllBytes(path));
    }

    @Test void intentionallyEmptyConfigStaysEmpty() throws Exception {
        Path path = directory.resolve("config.xml");
        var store = new ConfigStore(path);
        store.save(new Config(List.of(), false, false, false));
        assertEquals(List.of(), store.load().entries());
    }

    @Test void pasteActionsRetreatCaretWithExtendedLeft() {
        var none = InputSimulator.pasteActions(0);
        assertEquals(4, none.size());
        assertEquals(List.of(false, false, true, true), none.stream().map(InputSimulator.KeyAction::up).toList());
        var one = InputSimulator.pasteActions(1);
        assertEquals(6, one.size());
        assertEquals(0x25, one.get(4).virtualKey());
        assertTrue(one.get(4).extended());
        assertFalse(one.get(4).up());
        assertTrue(one.get(5).up() && one.get(5).extended());
        var two = InputSimulator.pasteActions(2);
        assertEquals(8, two.size());
        assertEquals(List.of((int) 'V', 0xA2), InputSimulator.stuckKeys(two, 2, key -> false));
        assertEquals(List.of(0xA2), InputSimulator.stuckKeys(two, 3, key -> false));
        assertEquals(List.of(), InputSimulator.stuckKeys(two, 4, key -> false));
        assertEquals(List.of(0x25), InputSimulator.stuckKeys(two, 5, key -> false));
        assertEquals(List.of(0xA2), InputSimulator.stuckKeys(two, 2, key -> key == 'V'));
        assertEquals(List.of((int) 'V'), InputSimulator.stuckKeys(two, 2, key -> key == 0x11));
    }
}
