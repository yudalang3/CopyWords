package cn.copywords;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapsHotkeysTest {
    @Test void capsIsTrackedWheneverRunningSoWritingShortcutsDoNotNeedPhrases() {
        assertTrue(CapsHotkeys.trackCaps(false, false));
        assertTrue(CapsHotkeys.trackCaps(true, false));
        assertTrue(CapsHotkeys.trackCaps(true, true));
        assertFalse(CapsHotkeys.trackCaps(false, true));
    }

    @Test void writingKeysFireOnceAndOnlyWhileNeitherCapturingNorPaused() {
        assertTrue(CapsHotkeys.fireWritingKey(false, false, false));
        assertFalse(CapsHotkeys.fireWritingKey(true, false, false));
        assertFalse(CapsHotkeys.fireWritingKey(false, true, false));
        assertFalse(CapsHotkeys.fireWritingKey(false, false, true));
    }
}
