package cn.copywords;

import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Properties;

/** Window geometry is saved independently of the editable shortcut configuration. */
public record WindowState(int x, int y, int width, int height, boolean maximized) {
    public WindowState {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("窗口尺寸无效");
    }

    public Rectangle bounds() { return new Rectangle(x, y, width, height); }

    public static WindowState load(Path path) throws IOException {
        if (!Files.exists(path)) return null;
        var properties = new Properties();
        try (var input = Files.newInputStream(path)) { properties.loadFromXML(input); }
        try {
            return new WindowState(Integer.parseInt(properties.getProperty("x")),
                    Integer.parseInt(properties.getProperty("y")),
                    Integer.parseInt(properties.getProperty("width")),
                    Integer.parseInt(properties.getProperty("height")),
                    Boolean.parseBoolean(properties.getProperty("maximized", "false")));
        } catch (RuntimeException exception) { throw new IOException("窗口状态文件内容无效", exception); }
    }

    public void save(Path path) throws IOException {
        var properties = new Properties();
        properties.setProperty("x", Integer.toString(x));
        properties.setProperty("y", Integer.toString(y));
        properties.setProperty("width", Integer.toString(width));
        properties.setProperty("height", Integer.toString(height));
        properties.setProperty("maximized", Boolean.toString(maximized));
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "window-state-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                properties.storeToXML(output, "CopyWords window state", "UTF-8");
            }
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    public WindowState fitToScreens(List<Rectangle> screens) {
        Rectangle original = bounds();
        Rectangle screen = screens.getFirst();
        long largestIntersection = -1;
        for (Rectangle candidate : screens) {
            Rectangle intersection = original.intersection(candidate);
            long area = intersection.isEmpty() ? 0 : (long) intersection.width * intersection.height;
            if (area > largestIntersection) { screen = candidate; largestIntersection = area; }
            if (candidate.contains(original) && width >= 660 && height >= 440) return this;
        }
        int fittedWidth = Math.min(Math.max(660, width), screen.width);
        int fittedHeight = Math.min(Math.max(440, height), screen.height);
        int fittedX = Math.max(screen.x, Math.min(x, screen.x + screen.width - fittedWidth));
        int fittedY = Math.max(screen.y, Math.min(y, screen.y + screen.height - fittedHeight));
        return new WindowState(fittedX, fittedY, fittedWidth, fittedHeight, maximized);
    }
}
