package cn.copywords;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.awt.Rectangle;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WindowStateTest {
    @TempDir Path directory;

    @Test void stateRoundTripIsIndependentOfTextConfiguration() throws Exception {
        Path config = directory.resolve("config.xml");
        new ConfigStore(config).save(Config.defaults());
        byte[] savedConfig = Files.readAllBytes(config);
        Path path = directory.resolve("window-state.xml");
        var original = new WindowState(-1200, 60, 760, 900, true);
        original.save(path);
        assertEquals(original, WindowState.load(path));
        new WindowState(100, 80, 680, 460, false).save(path);
        assertEquals(680, WindowState.load(path).width());
        assertArrayEquals(savedConfig, Files.readAllBytes(config));
        try (var files = Files.list(directory)) { assertEquals(2, files.count()); }
    }

    @Test void missingAndCorruptStateAllowDefaultFallbackWithoutOverwrite() throws Exception {
        Path path = directory.resolve("window-state.xml");
        assertNull(WindowState.load(path));
        Files.writeString(path, "broken xml");
        assertThrows(java.io.IOException.class, () -> WindowState.load(path));
        assertEquals("broken xml", Files.readString(path));
        assertThrows(IllegalArgumentException.class, () -> new WindowState(0, 0, -1, 500, false));
    }

    @Test void invalidNumericStateIsRejected() throws Exception {
        Path path = directory.resolve("window-state.xml");
        var properties = new java.util.Properties();
        properties.setProperty("x", "not a number");
        try (var output = Files.newOutputStream(path)) { properties.storeToXML(output, "invalid"); }
        assertThrows(java.io.IOException.class, () -> WindowState.load(path));
    }

    @Test void validNegativeMonitorCoordinatesArePreserved() {
        var original = new WindowState(-1200, 100, 760, 800, true);
        var screens = List.of(new Rectangle(0, 0, 1920, 1040), new Rectangle(-1600, 0, 1600, 900));
        assertEquals(original, original.fitToScreens(screens));
    }

    @Test void removedMonitorAndSmallerScreenKeepWindowVisible() {
        var screen = new Rectangle(0, 0, 1280, 680);
        var adjusted = new WindowState(-1600, 700, 1600, 1280, true).fitToScreens(List.of(screen));
        assertEquals(new WindowState(0, 0, 1280, 680, true), adjusted);
        assertTrue(screen.contains(adjusted.bounds()));
        var minimum = new WindowState(1260, 660, 10, 10, false).fitToScreens(List.of(screen));
        assertEquals(new WindowState(620, 240, 660, 440, false), minimum);
        var overlapping = new WindowState(-900, 20, 760, 600, false).fitToScreens(
                List.of(screen, new Rectangle(-1000, 0, 1000, 680)));
        assertEquals(-900, overlapping.x());
    }

    @Test void failedReplacementKeepsExistingTarget() throws Exception {
        Path path = directory.resolve("window-state.xml");
        Files.createDirectory(path);
        Files.writeString(path.resolve("keep.txt"), "keep");
        assertThrows(java.io.IOException.class, () -> new WindowState(0, 0, 760, 900, false).save(path));
        assertEquals("keep", Files.readString(path.resolve("keep.txt")));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void trayFontCanDisplayChineseLabels() {
        assertEquals(-1, CopyWords.trayMenuFont().canDisplayUpTo("打开设置退出"));
    }
}
