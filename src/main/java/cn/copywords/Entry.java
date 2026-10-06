package cn.copywords;

import java.util.UUID;

public record Entry(String id, String text, Hotkey hotkey) {
    public static Entry create(String text, Hotkey hotkey) {
        return new Entry(UUID.randomUUID().toString(), text, hotkey);
    }
    public boolean active() { return !text.isEmpty() && hotkey != null; }
}
