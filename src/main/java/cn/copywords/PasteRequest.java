package cn.copywords;

import java.util.function.IntPredicate;

public record PasteRequest(String text, int triggerKey, Target target, long triggeredAt, IntPredicate heldKeys) {
    public record Target(long window, long focus, int thread, int process) {
        public boolean known() { return window != 0 && thread != 0; }
    }
}
