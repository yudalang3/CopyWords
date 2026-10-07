package cn.copywords;

import java.util.List;
import java.util.HashSet;

public record Config(List<Entry> entries, boolean autoStart, boolean startHidden, boolean closeToTray) {
    public Config { entries = List.copyOf(entries); }

    public static Config defaults() {
        return new Config(List.of(Entry.create("AI4S", new Hotkey(Hotkey.CAPS, '1')),
                Entry.create("人生价值", new Hotkey(Hotkey.CAPS, '2')),
                Entry.create("xxx@example.com", new Hotkey(Hotkey.CAPS, '3')),
                Entry.create("", null), Entry.create("", null)), true, true, false);
    }

    public Config upgradeLegacyDefaults() {
        var result = new java.util.ArrayList<Entry>(entries);
        var reserved = new HashSet<Hotkey>();
        entries.forEach(entry -> { if (entry.hotkey() != null && entry.hotkey().usesCaps()) reserved.add(entry.hotkey()); });
        for (int i = 0; i < result.size(); i++) {
            Entry entry = result.get(i);
            Hotkey key = entry.hotkey();
            boolean oldAltDigit = key != null && key.modifiers() == Hotkey.ALT && key.key() >= '0' && key.key() <= '9';
            boolean oldSecondDefault = i == 1 && key != null && key.equals(new Hotkey(Hotkey.CTRL | Hotkey.SHIFT, '2'));
            if (!oldAltDigit && !oldSecondDefault) continue;
            Hotkey replacement = new Hotkey(Hotkey.CAPS, key.key());
            if (reserved.contains(replacement)) {
                replacement = null;
                for (char digit : "1234567890".toCharArray()) {
                    Hotkey available = new Hotkey(Hotkey.CAPS, digit);
                    if (!reserved.contains(available)) { replacement = available; break; }
                }
            }
            if (replacement != null) reserved.add(replacement);
            result.set(i, new Entry(entry.id(), entry.text(), replacement));
        }
        return new Config(result, autoStart, startHidden, closeToTray);
    }

    public void validate() {
        validate(true);
    }

    void validateLoaded() { validate(false); }

    private void validate(boolean rejectPasteHotkey) {
        var keys = new HashSet<Hotkey>();
        var ids = new HashSet<String>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (!ids.add(entry.id())) throw new IllegalArgumentException("条目 ID 重复");
            if (entry.text().contains("\n") || entry.text().contains("\r"))
                throw new IllegalArgumentException("第 " + (i + 1) + " 行只能使用单行文本");
            if (rejectPasteHotkey && entry.hotkey() != null && entry.hotkey().reservedForPaste())
                throw new IllegalArgumentException("第 " + (i + 1) + " 行：" + Hotkey.PASTE_ADVICE);
            if (entry.hotkey() != null && !keys.add(entry.hotkey()))
                throw new IllegalArgumentException("第 " + (i + 1) + " 行快捷键重复：" + entry.hotkey());
        }
    }
}
