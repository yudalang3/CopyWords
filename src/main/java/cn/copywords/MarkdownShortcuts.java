package cn.copywords;

/** Fixed writing shortcuts. These are not phrase-list entries. */
final class MarkdownShortcuts {
    static final int MINUS = 0xBD, EQUALS = 0xBB, BACKSLASH = 0xDC;

    record Action(String text, int caretFromEnd) {}

    static boolean reservedKey(int virtualKey) {
        return virtualKey == MINUS || virtualKey == EQUALS || virtualKey == BACKSLASH;
    }

    static Action match(int virtualKey) {
        if (virtualKey == MINUS) return new Action("``", 1);
        if (virtualKey == EQUALS) return new Action("****", 2);
        if (virtualKey == BACKSLASH) return new Action("**", 0);
        return null;
    }

    private MarkdownShortcuts() {}
}
