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

    public void validate() {
        var keys = new HashSet<Hotkey>();
        var ids = new HashSet<String>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (!ids.add(entry.id())) throw new IllegalArgumentException("条目 ID 重复");
            if (entry.text().contains("\n") || entry.text().contains("\r"))
                throw new IllegalArgumentException("第 " + (i + 1) + " 行只能使用单行文本");
            if (entry.hotkey() != null && entry.hotkey().reservedForPaste())
                throw new IllegalArgumentException("第 " + (i + 1) + " 行：" + Hotkey.PASTE_ADVICE);
            if (entry.hotkey() != null && !keys.add(entry.hotkey()))
                throw new IllegalArgumentException("第 " + (i + 1) + " 行快捷键重复：" + entry.hotkey());
        }
    }
}
