package cn.copywords;

import java.awt.event.KeyEvent;
import java.util.HashSet;
import java.util.List;

public record Hotkey(int modifiers, int key) {
    public static final int ALT = 1, CTRL = 2, SHIFT = 4, CAPS = 8;
    public static final String PASTE_ADVICE = "Ctrl + V 用于自动粘贴，请选择其他快捷键";

    public Hotkey {
        if (modifiers < 1 || modifiers > 15 || !supported(key))
            throw new IllegalArgumentException("请使用 Caps / Ctrl / Shift / Alt 加字母、数字或 F1–F11");
    }

    public static boolean supported(int key) {
        return key >= '0' && key <= '9' || key >= 'A' && key <= 'Z'
                || key >= KeyEvent.VK_F1 && key <= KeyEvent.VK_F11;
    }

    public static Hotkey from(KeyEvent event) {
        if (event.isMetaDown() || event.isAltGraphDown())
            throw new IllegalArgumentException("请使用 Caps / Ctrl / Shift / Alt 组合键");
        int modifiers = (event.isAltDown() ? ALT : 0) | (event.isControlDown() ? CTRL : 0)
                | (event.isShiftDown() ? SHIFT : 0);
        return new Hotkey(modifiers, event.getKeyCode());
    }

    public static Hotkey nextDefault(List<Entry> entries) {
        var used = new HashSet<Hotkey>();
        entries.forEach(entry -> { if (entry.hotkey() != null) used.add(entry.hotkey()); });
        for (char digit : "1234567890".toCharArray()) {
            var hotkey = new Hotkey(CAPS, digit);
            if (!used.contains(hotkey)) return hotkey;
        }
        return null;
    }

    @Override public String toString() {
        return ((modifiers & CAPS) != 0 ? "Caps + " : "")
                + ((modifiers & CTRL) != 0 ? "Ctrl + " : "")
                + ((modifiers & SHIFT) != 0 ? "Shift + " : "")
                + ((modifiers & ALT) != 0 ? "Alt + " : "") + KeyEvent.getKeyText(key);
    }

    public boolean usesCaps() { return (modifiers & CAPS) != 0; }
    public boolean reservedForPaste() { return modifiers == CTRL && key == 'V'; }
}
