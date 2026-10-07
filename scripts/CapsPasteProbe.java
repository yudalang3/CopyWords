import cn.copywords.*;
import java.nio.file.*;
import java.awt.Toolkit;
import java.awt.datatransfer.Transferable;

// Keep the production Caps/clipboard services active while testing Word and a browser.
public class CapsPasteProbe {
    public static void main(String[] args) throws Exception {
        Path signals = Path.of(args[0]); Files.createDirectories(signals);
        Transferable previous = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
        try (var clipboard = new ClipboardService(message -> {
            try { Files.writeString(signals.resolve("copied.txt"), message); }
            catch (Exception exception) { throw new RuntimeException(exception); }
        }); var hotkeys = new GlobalHotkeys(clipboard::paste);
             var watcher = signals.getFileSystem().newWatchService()) {
            signals.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            var warnings = hotkeys.start(Config.defaults());
            if (!warnings.isEmpty()) throw new IllegalStateException(String.join("\n", warnings));
            Files.writeString(signals.resolve("ready.txt"), "Caps+1=AI4S; Caps+2=人生价值; Caps+3=xxx@example.com");
            while (!Files.exists(signals.resolve("stop.txt"))) { var change = watcher.take(); change.pollEvents(); change.reset(); }
        } finally {
            if (previous != null) Toolkit.getDefaultToolkit().getSystemClipboard().setContents(previous, null);
        }
    }
}
