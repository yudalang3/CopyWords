package cn.copywords;

import javax.swing.*;
import javax.imageio.ImageIO;
import javax.swing.border.*;
import javax.swing.plaf.FontUIResource;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.BaseMultiResolutionImage;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;

public final class CopyWords {
    private static final List<BufferedImage> ICONS = loadIcons();
    private final JFrame frame = new JFrame("CopyWords");
    private final JPanel rowsPanel = new JPanel();
    private final List<Row> rows = new ArrayList<>();
    private final JCheckBox autoStart = new JCheckBox("Windows 启动时自动运行");
    private final JCheckBox startHidden = new JCheckBox("启动后最小化到系统托盘");
    private final JCheckBox closeToTray = new JCheckBox("点击窗口关闭按钮时，隐藏到系统托盘继续运行（不勾选则退出程序）");
    private final JLabel status = new JLabel("设置会自动保存；空文本条目不启用快捷键");
    private final JLabel saveStatus = new JLabel("设置会自动保存");
    private final JButton retry = new JButton("重试");
    private final javax.swing.Timer saveTimer = new javax.swing.Timer(500, event -> saveLatest());
    private final ConfigStore store;
    private final Path windowStatePath;
    private Rectangle normalBounds;
    private boolean windowMaximized;
    private final AutoStart startup;
    private final ClipboardService clipboard;
    private final GlobalHotkeys hotkeys;
    private Config saved;
    private TrayIcon tray;
    private JPopupMenu trayMenu;
    private JDialog trayMenuOwner;
    private boolean saving, exiting;
    private boolean ready, capturing, confirmingText, pendingSave, userSaveRequested, startupSynced, saveCompleted, closingForExit;
    private Row pendingCapture;
    private Runnable afterSave;
    private FileChannel lockChannel;
    private FileLock lock;

    private CopyWords(Path home, FileChannel lockChannel, FileLock lock) throws Exception {
        this.lockChannel = lockChannel;
        this.lock = lock;
        store = new ConfigStore(home.resolve("config.xml"));
        windowStatePath = home.resolve("window-state.xml");
        Path jar = Path.of(CopyWords.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        startup = new AutoStart(System.getProperty("copywords.autostartName", "CopyWords"), AutoStart.command(jar));
        boolean firstRun = !store.exists();
        String loadWarning = null;
        try { saved = firstRun ? Config.defaults() : store.load(); }
        catch (Exception exception) {
            saved = Config.defaults();
            firstRun = true;
            loadWarning = "读取配置失败，已显示示例条目；原文件未修改。\n" + exception.getMessage();
            exception.printStackTrace();
        }
        clipboard = new ClipboardService(this::setStatus);
        hotkeys = new GlobalHotkeys(clipboard::paste);
        buildWindow();
        setupTray();
        var warnings = hotkeys.start(saved);
        ready = true;
        if (firstRun || !saved.startHidden() || tray == null || !warnings.isEmpty()) showWindow();
        if (loadWarning != null) showError(loadWarning);
        if (!warnings.isEmpty()) showError(String.join("\n", warnings));
    }

    private void buildWindow() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                if (closeToTray.isSelected() && tray != null) {
                    flushAutoSave(() -> { saveWindowState(); frame.setVisible(false); });
                } else requestExit();
            }
        });
        frame.setIconImages(ICONS);
        JPanel content = new JPanel(new BorderLayout(0, 20));
        content.setBorder(new EmptyBorder(24, 26, 20, 26));
        JPanel heading = new JPanel(new BorderLayout(0, 8));
        JLabel title = new JLabel("CopyWords");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28));
        heading.add(title, BorderLayout.NORTH);
        heading.add(new JLabel("按快捷键自动复制并粘贴；Ctrl / Shift / 左 Alt 组合请松开按键。"), BorderLayout.SOUTH);
        content.add(heading, BorderLayout.NORTH);

        JPanel list = new JPanel(new BorderLayout(0, 8));
        JPanel labels = new JPanel(new BorderLayout(12, 0));
        JLabel shortcutLabel = new JLabel("快捷键");
        shortcutLabel.setPreferredSize(new Dimension(170, 24));
        labels.add(shortcutLabel, BorderLayout.WEST);
        labels.add(new JLabel("文本内容"), BorderLayout.CENTER);
        JLabel spacer = new JLabel(); spacer.setPreferredSize(new Dimension(70, 24));
        labels.add(spacer, BorderLayout.EAST);
        list.add(labels, BorderLayout.NORTH);
        rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));
        saved.entries().forEach(this::addRow);
        JPanel rowsWrapper = new RowsViewport();
        rowsWrapper.add(rowsPanel, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(rowsWrapper);
        scroll.setBorder(new LineBorder(new Color(215, 220, 225)));
        scroll.getVerticalScrollBar().setUnitIncrement(28);
        list.add(scroll, BorderLayout.CENTER);
        JButton add = new JButton("+ 添加");
        add.setName("add");
        add.addActionListener(event -> {
            addRow(Entry.create("", Hotkey.nextDefault(draft().entries())), false);
            rowsPanel.revalidate(); rowsPanel.repaint();
            SwingUtilities.invokeLater(() -> {
                Row last = rows.getLast();
                last.text.requestFocusInWindow();
                rowsPanel.scrollRectToVisible(last.panel.getBounds());
            });
        });
        JPanel addPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        addPanel.add(add);
        list.add(addPanel, BorderLayout.SOUTH);
        content.add(list, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 14));
        JPanel options = new JPanel();
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        autoStart.setSelected(saved.autoStart()); startHidden.setSelected(saved.startHidden());
        autoStart.setName("autoStart"); startHidden.setName("startHidden");
        closeToTray.setSelected(saved.closeToTray()); closeToTray.setName("closeToTray");
        options.add(autoStart); options.add(startHidden); options.add(closeToTray);
        for (JCheckBox option : List.of(autoStart, startHidden, closeToTray))
            option.addItemListener(event -> requestAutoSave(true));
        bottom.add(options, BorderLayout.NORTH);
        JPanel footer = new JPanel(new BorderLayout(12, 0));
        status.setFont(status.getFont().deriveFont(12f));
        status.setForeground(new Color(90, 100, 110));
        saveTimer.setRepeats(false);
        saveStatus.setName("saveStatus");
        saveStatus.setFont(saveStatus.getFont().deriveFont(12f));
        saveStatus.setPreferredSize(new Dimension(110, 34));
        retry.setName("retry"); retry.setVisible(false);
        retry.addActionListener(event -> flushAutoSave(null));
        JPanel saveFeedback = new JPanel(new BorderLayout(12, 0));
        saveFeedback.add(saveStatus, BorderLayout.CENTER); saveFeedback.add(retry, BorderLayout.EAST);
        footer.add(status, BorderLayout.CENTER); footer.add(saveFeedback, BorderLayout.EAST);
        bottom.add(footer, BorderLayout.SOUTH);
        content.add(bottom, BorderLayout.SOUTH);
        frame.setContentPane(content);
        frame.setSize(760, Math.min(1280, GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds().height));
        frame.setMinimumSize(new Dimension(660, 440));
        frame.setLocationRelativeTo(null);
        try {
            WindowState state = WindowState.load(windowStatePath);
            if (state != null) {
                state = state.fitToScreens(usableScreens());
                frame.setBounds(state.bounds());
                windowMaximized = state.maximized();
            }
        } catch (Exception exception) {
            exception.printStackTrace();
            setStatus("未能读取窗口位置，已使用默认窗口；可重新调整后关闭以记住位置。");
        }
        normalBounds = frame.getBounds();
        if (windowMaximized) frame.setExtendedState(Frame.MAXIMIZED_BOTH);
        frame.addComponentListener(new ComponentAdapter() {
            @Override public void componentMoved(ComponentEvent event) { rememberNormalBounds(); }
            @Override public void componentResized(ComponentEvent event) { rememberNormalBounds(); }
        });
        frame.addWindowStateListener(event -> {
            if ((event.getNewState() & Frame.ICONIFIED) == 0)
                windowMaximized = (event.getNewState() & Frame.MAXIMIZED_BOTH) != 0;
        });
    }

    private static List<Rectangle> usableScreens() {
        var screens = new ArrayList<Rectangle>();
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration configuration = device.getDefaultConfiguration();
            Rectangle bounds = new Rectangle(configuration.getBounds());
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
            bounds.x += insets.left; bounds.y += insets.top;
            bounds.width -= insets.left + insets.right; bounds.height -= insets.top + insets.bottom;
            screens.add(bounds);
        }
        return screens;
    }

    private void rememberNormalBounds() {
        if (frame.isShowing() && frame.getExtendedState() == Frame.NORMAL) normalBounds = frame.getBounds();
    }

    private void saveWindowState() {
        rememberNormalBounds();
        try {
            new WindowState(normalBounds.x, normalBounds.y, normalBounds.width, normalBounds.height,
                    windowMaximized).save(windowStatePath);
        } catch (Exception exception) {
            exception.printStackTrace();
            showError("未能记住窗口位置和大小。请检查配置目录是否可写或磁盘是否已满。\n文本配置不受影响，程序仍会正常关闭。");
        }
    }

    private void addRow(Entry entry) {
        addRow(entry, true);
    }

    private void addRow(Entry entry, boolean included) {
        var row = new Row(entry, included);
        rows.add(row);
        rowsPanel.add(row.panel);
    }

    private static final class RowsViewport extends JPanel implements Scrollable {
        RowsViewport() { super(new BorderLayout()); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 28; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(28, visible.height - 28); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private final class Row {
        final String id;
        Hotkey hotkey;
        final JPanel panel = new JPanel(new BorderLayout(12, 0));
        final JButton shortcut = new JButton();
        final JTextField text;
        final Border normalTextBorder;
        String saveableText;
        boolean included, composing;
        Row(Entry entry, boolean included) {
            id = entry.id(); hotkey = entry.hotkey();
            saveableText = entry.text(); this.included = included;
            text = new JTextField(entry.text()); text.setName("text-" + id);
            text.setBorder(new CompoundBorder(text.getBorder(), new EmptyBorder(0, 8, 0, 0)));
            normalTextBorder = text.getBorder();
            panel.setBorder(new EmptyBorder(7, 8, 7, 8));
            panel.setPreferredSize(new Dimension(600, 50));
            panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
            shortcut.setPreferredSize(new Dimension(170, 34));
            refresh(); shortcut.setName("hotkey-" + id);
            shortcut.addActionListener(event -> capture(this));
            JButton delete = new JButton("删除"); delete.setPreferredSize(new Dimension(70, 34));
            delete.setName("delete-" + id);
            delete.addActionListener(event -> {
                rows.remove(this); rowsPanel.remove(panel);
                rowsPanel.revalidate(); rowsPanel.repaint();
                if (this.included) requestAutoSave(true);
            });
            panel.add(shortcut, BorderLayout.WEST); panel.add(text, BorderLayout.CENTER);
            panel.add(delete, BorderLayout.EAST);
            text.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent event) { changed(); }
                @Override public void removeUpdate(DocumentEvent event) { changed(); }
                @Override public void changedUpdate(DocumentEvent event) { changed(); }
                private void changed() { SwingUtilities.invokeLater(() -> readText(false)); }
            });
            text.addInputMethodListener(new InputMethodListener() {
                @Override public void inputMethodTextChanged(InputMethodEvent event) {
                    var characters = event.getText();
                    composing = characters != null && event.getCommittedCharacterCount()
                            < characters.getEndIndex() - characters.getBeginIndex();
                    SwingUtilities.invokeLater(() -> readText(false));
                }
                @Override public void caretPositionChanged(InputMethodEvent event) { }
            });
            text.addFocusListener(new FocusAdapter() {
                @Override public void focusLost(FocusEvent event) {
                    SwingUtilities.invokeLater(() -> readText(true));
                }
            });
            text.addActionListener(event -> {
                text.getInputContext().endComposition();
                SwingUtilities.invokeLater(() -> readText(true));
            });
        }
        void readText(boolean confirmEmpty) {
            if (!ready || exiting || !rows.contains(this) || composing) return;
            String value = text.getText();
            if (value.isEmpty() && !confirmEmpty) {
                if (!saveableText.isEmpty()) setSaveStatus("文本暂为空；离开文本框后确认清空", false);
                return;
            }
            if (!value.isEmpty()) included = true;
            if (value.equals(saveableText)) return;
            saveableText = value;
            requestAutoSave(confirmEmpty);
        }
        void refresh() { shortcut.setText(hotkey == null ? "点击设置" : hotkey.toString()); }
        Entry entry() { return new Entry(id, text.getText(), hotkey); }
    }

    private Config draft() { return new Config(rows.stream().map(Row::entry).toList(), autoStart.isSelected(), startHidden.isSelected(), closeToTray.isSelected()); }

    private Config buildSaveCandidate() {
        return new Config(rows.stream().filter(row -> row.included)
                .map(row -> new Entry(row.id, row.saveableText, row.hotkey)).toList(),
                autoStart.isSelected(), startHidden.isSelected(), closeToTray.isSelected());
    }

    private void capture(Row row) {
        if (exiting || afterSave != null || !rows.contains(row)) return;
        if (saving) { pendingCapture = row; return; }
        capturing = true;
        saveTimer.stop();
        clipboard.pause();
        try { hotkeys.pause(); }
        catch (Exception exception) {
            capturing = false; clipboard.resume(); showError(exception.getMessage());
            if (pendingSave) saveLatest();
            return;
        }
        var dialog = new JDialog(frame, "设置快捷键", true);
        var candidate = new Hotkey[] { row.hotkey };
        var invalid = new boolean[1];
        var accepted = new boolean[1];
        JLabel hint = new JLabel("按住 Caps / Ctrl / Shift / 左 Alt，再按字母、数字或 F1–F11");
        JLabel value = new JLabel(row.hotkey == null ? "尚未设置" : row.hotkey.toString(), SwingConstants.CENTER);
        value.setFont(value.getFont().deriveFont(Font.BOLD, 22));
        JPanel center = new JPanel(new BorderLayout(0, 22));
        center.setBorder(new EmptyBorder(24, 24, 24, 24));
        center.add(hint, BorderLayout.NORTH); center.add(value, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton clear = new JButton("清除"), cancel = new JButton("取消"), accept = new JButton("确定");
        clear.addActionListener(event -> {
            row.hotkey = null; row.included = true; accepted[0] = true; row.refresh(); dialog.dispose();
        });
        cancel.addActionListener(event -> dialog.dispose());
        accept.addActionListener(event -> {
            if (invalid[0]) { hint.setText(Hotkey.LEFT_ALT_ADVICE); return; }
            if (candidate[0] != null && candidate[0].reservedForPaste()) {
                hint.setText(Hotkey.PASTE_ADVICE); return;
            }
            if (candidate[0] != null && rows.stream().anyMatch(other -> other != row && candidate[0].equals(other.hotkey))) {
                hint.setText("此快捷键已被其他条目使用，请重新设置"); return;
            }
            row.hotkey = candidate[0]; row.included = true; accepted[0] = true; row.refresh(); dialog.dispose();
        });
        buttons.add(clear); buttons.add(cancel); buttons.add(accept);
        center.add(buttons, BorderLayout.SOUTH); dialog.setContentPane(center);
        dialog.setSize(530, 220); dialog.setResizable(false); dialog.setLocationRelativeTo(frame);
        KeyEventDispatcher dispatcher = event -> {
            if (!dialog.isShowing() || event.getID() != KeyEvent.KEY_PRESSED) return false;
            int code = event.getKeyCode();
            if (code == KeyEvent.VK_ESCAPE) { dialog.dispose(); return true; }
            if (code == KeyEvent.VK_ALT_GRAPH || code == KeyEvent.VK_ALT && event.getKeyLocation() == KeyEvent.KEY_LOCATION_RIGHT) {
                invalid[0] = true; hint.setText(Hotkey.LEFT_ALT_ADVICE); return true;
            }
            if (code == KeyEvent.VK_ALT || code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_SHIFT) return true;
            try {
                candidate[0] = Hotkey.from(event, hotkeys.rightAltDown());
                invalid[0] = false;
                value.setText(candidate[0].toString());
                hint.setText("点击“确定”后自动保存并生效");
            } catch (IllegalArgumentException exception) {
                if (hotkeys.rightAltDown() || event.isAltGraphDown()) invalid[0] = true;
                hint.setText(exception.getMessage());
            }
            return true;
        };
        KeyboardFocusManager manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        manager.addKeyEventDispatcher(dispatcher);
        hotkeys.capture(key -> SwingUtilities.invokeLater(() -> {
            if (!dialog.isShowing()) return;
            candidate[0] = key; value.setText(key.toString());
            invalid[0] = false;
            hint.setText("点击“确定”后自动保存并生效");
        }));
        try { dialog.setVisible(true); }
        finally {
            hotkeys.capture(null);
            manager.removeKeyEventDispatcher(dispatcher);
            try {
                var warnings = hotkeys.resume();
                if (!warnings.isEmpty()) showError(String.join("\n", warnings));
            } catch (Exception exception) { showError(exception.getMessage()); }
            finally { clipboard.resume(); capturing = false; }
            if (accepted[0]) requestAutoSave(true);
            else if (pendingSave || afterSave != null) saveLatest();
        }
    }

    private void requestAutoSave(boolean immediate) {
        if (!ready || exiting) return;
        userSaveRequested = true;
        pendingSave = true;
        setSaveStatus("等待保存…", false);
        if (confirmingText || capturing) return;
        if (immediate) { saveTimer.stop(); saveLatest(); }
        else scheduleAutoSave();
    }

    private void scheduleAutoSave() {
        if (!capturing && !exiting) saveTimer.restart();
    }

    private void flushAutoSave(Runnable action) {
        if (exiting || afterSave != null) return;
        saveTimer.stop();
        afterSave = action;
        if (action != null) frame.setEnabled(false);
        for (Row row : rows) row.text.getInputContext().endComposition();
        SwingUtilities.invokeLater(() -> {
            confirmingText = true;
            try { for (Row row : rows) row.readText(true); }
            finally { confirmingText = false; }
            saveTimer.stop();
            pendingSave = true;
            saveLatest();
        });
    }

    private void saveLatest() {
        if (!ready || exiting) return;
        if (saving || capturing || confirmingText) { pendingSave = true; return; }
        saveTimer.stop();
        pendingSave = false;
        Config candidate = buildSaveCandidate();
        boolean syncStartup = userSaveRequested && (!startupSynced || candidate.autoStart() != saved.autoStart());
        if (candidate.equals(saved) && !syncStartup && (store.exists() || !userSaveRequested)) {
            finishAutoSave();
            return;
        }
        rows.forEach(row -> { row.text.setBorder(row.normalTextBorder); row.text.setToolTipText(null); });
        try { candidate.validate(); }
        catch (Exception exception) { saveFailed(candidate, exception.getMessage()); return; }
        saving = true;
        setSaveStatus("正在保存…", false);
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                hotkeys.apply(candidate, () -> {
                    String previous = syncStartup ? startup.snapshot() : null;
                    try {
                        if (syncStartup) startup.set(candidate.autoStart());
                        store.save(candidate);
                    }
                    catch (Exception exception) {
                        try { if (syncStartup) startup.restore(previous); }
                        catch (Exception rollback) {
                            exception.addSuppressed(rollback);
                            throw new IllegalStateException(exception.getMessage() + "；恢复自启动设置失败，请检查启动设置", exception);
                        }
                        throw exception;
                    }
                });
                return null;
            }
            @Override protected void done() {
                saving = false;
                try {
                    get(); saved = candidate;
                    saveCompleted = true;
                    if (syncStartup) startupSynced = true;
                }
                catch (Exception exception) {
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    cause.printStackTrace(); saveFailed(candidate, cause.getMessage());
                    openPendingCapture();
                    if (pendingSave && !candidate.equals(buildSaveCandidate()) && !saveTimer.isRunning()) saveLatest();
                    return;
                }
                if (openPendingCapture()) return;
                if (pendingSave || !candidate.equals(buildSaveCandidate())
                        || userSaveRequested && !startupSynced) {
                    setSaveStatus("等待保存…", false);
                    if (!saveTimer.isRunning() || afterSave != null) saveLatest();
                } else finishAutoSave();
            }
        }.execute();
    }

    private boolean openPendingCapture() {
        Row row = pendingCapture;
        pendingCapture = null;
        if (row == null || !rows.contains(row) || afterSave != null) return false;
        capture(row);
        if (!saving && !capturing && afterSave == null && !pendingSave) finishAutoSave();
        return true;
    }

    private void finishAutoSave() {
        boolean emptyDraft = rows.stream().anyMatch(row -> row.included && row.text.getText().isEmpty()
                && !row.saveableText.isEmpty());
        setSaveStatus(emptyDraft ? "文本暂为空；离开文本框后确认清空"
                : saveCompleted ? "已自动保存" : "设置会自动保存", false);
        Runnable action = afterSave;
        afterSave = null;
        if (action != null) { frame.setEnabled(true); action.run(); }
    }

    private void setSaveStatus(String message, boolean failed) {
        saveStatus.setText(failed ? "保存失败，请重试" : message.startsWith("文本暂为空") ? "空文本待确认" : message);
        saveStatus.setToolTipText(message);
        saveStatus.setForeground(failed ? new Color(190, 55, 55) : new Color(90, 100, 110));
        retry.setVisible(failed);
    }

    private void saveFailed(Config candidate, String message) {
        if (message == null) message = "保存失败，请检查配置目录是否可写。";
        var match = Pattern.compile("第 (\\d+) 行").matcher(message);
        if (match.find()) {
            int index = Integer.parseInt(match.group(1)) - 1;
            if (index >= 0 && index < candidate.entries().size()) {
                String id = candidate.entries().get(index).id();
                for (Row row : rows) if (row.id.equals(id)) {
                    row.text.setBorder(new CompoundBorder(new LineBorder(new Color(190, 55, 55), 2), new EmptyBorder(0, 8, 0, 0)));
                    row.text.setToolTipText(message);
                    rowsPanel.scrollRectToVisible(row.panel.getBounds());
                    break;
                }
            }
        }
        setSaveStatus("保存失败，上次生效的内容保持不变；" + message.replace('\n', ' '), true);
        boolean closing = afterSave != null;
        afterSave = null;
        frame.setEnabled(true);
        if (closing) {
            showWindow();
            if (closingForExit) {
                closingForExit = false;
                int answer = JOptionPane.showOptionDialog(frame, "退出前自动保存失败：\n" + message, "CopyWords",
                        JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                        new String[] {"重试并退出", "丢弃未保存修改并退出", "取消"}, "重试并退出");
                if (answer == 0) requestExit();
                else if (answer == 1) exit();
            } else showError("自动保存失败，窗口保持打开：\n" + message);
        }
    }

    private void setupTray() {
        if (!SystemTray.isSupported()) return;
        // Native AWT menus render Chinese as missing glyphs on this Java 25 build,
        // even with explicit CJK fonts. Swing uses the same working renderer as the settings window.
        trayMenu = new JPopupMenu();
        JMenuItem open = new JMenuItem("打开设置"), quit = new JMenuItem("退出");
        Font menuFont = trayMenuFont();
        trayMenu.setFont(menuFont); open.setFont(menuFont); quit.setFont(menuFont);
        open.addActionListener(event -> { hideTrayMenu(); showWindow(); });
        quit.addActionListener(event -> { hideTrayMenu(); requestExit(); });
        trayMenu.add(open); trayMenu.addSeparator(); trayMenu.add(quit);
        trayMenuOwner = new JDialog(frame);
        trayMenuOwner.setUndecorated(true);
        trayMenuOwner.setType(Window.Type.POPUP);
        trayMenuOwner.setAlwaysOnTop(true);
        trayMenuOwner.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowLostFocus(WindowEvent event) {
                SwingUtilities.invokeLater(() -> {
                    Window focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
                    for (Window owner = focused; owner != null; owner = owner.getOwner())
                        if (owner == trayMenuOwner) return;
                    hideTrayMenu();
                });
            }
        });
        trayMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(PopupMenuEvent event) { }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent event) { trayMenuOwner.setVisible(false); }
            @Override public void popupMenuCanceled(PopupMenuEvent event) { trayMenuOwner.setVisible(false); }
        });
        tray = new TrayIcon(new BaseMultiResolutionImage(ICONS.toArray(Image[]::new)),
                "CopyWords — 常用文本快捷粘贴");
        tray.setImageAutoSize(true);
        tray.addActionListener(event -> SwingUtilities.invokeLater(this::showWindow));
        tray.addMouseListener(new MouseAdapter() {
            private void popup(MouseEvent event) {
                if (event.isPopupTrigger()) SwingUtilities.invokeLater(() -> showTrayMenu(event.getX(), event.getY()));
            }
            @Override public void mousePressed(MouseEvent event) { popup(event); }
            @Override public void mouseReleased(MouseEvent event) { popup(event); }
        });
        try { SystemTray.getSystemTray().add(tray); }
        catch (AWTException exception) {
            tray = null; trayMenuOwner.dispose(); setStatus("系统托盘不可用，应用将保持窗口运行");
        }
    }

    private void showTrayMenu(int x, int y) {
        showTrayMenu(x, y, usableScreens());
    }

    private void showTrayMenu(int x, int y, List<Rectangle> screens) {
        hideTrayMenu();
        if (screens.isEmpty()) {
            System.err.println("暂无可用显示器，暂时无法显示托盘菜单，请稍后重试。");
            return;
        }
        Dimension size = trayMenu.getPreferredSize();
        Rectangle screen = screens.stream().filter(bounds -> bounds.contains(x, y)).findFirst().orElse(screens.getFirst());
        int left = Math.max(screen.x, Math.min(x, screen.x + screen.width - size.width));
        int top = Math.max(screen.y, Math.min(y, screen.y + screen.height - size.height));
        trayMenuOwner.setBounds(left, top, size.width, size.height);
        trayMenuOwner.setVisible(true);
        trayMenu.show(trayMenuOwner.getContentPane(), 0, 0);
        trayMenuOwner.requestFocus();
    }

    private void hideTrayMenu() {
        if (trayMenu != null) trayMenu.setVisible(false);
        if (trayMenuOwner != null) trayMenuOwner.setVisible(false);
    }

    static Font trayMenuFont() {
        String labels = "打开设置退出";
        for (String name : new String[] {"Microsoft YaHei UI", "Microsoft YaHei", "SimSun", Font.DIALOG}) {
            Font font = new Font(name, Font.PLAIN, 14);
            if (font.canDisplayUpTo(labels) == -1) return font;
        }
        for (Font font : GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts())
            if (font.canDisplayUpTo(labels) == -1) return font.deriveFont(Font.PLAIN, 14f);
        return new Font(Font.DIALOG, Font.PLAIN, 14);
    }

    private static List<BufferedImage> loadIcons() {
        var images = new ArrayList<BufferedImage>();
        for (int size : new int[] {16, 24, 32, 48, 64, 128, 256}) {
            String resource = "/cn/copywords/icons/copywords-" + size + ".png";
            try (var stream = CopyWords.class.getResourceAsStream(resource)) {
                if (stream == null) throw new IOException("缺少图标资源：" + resource);
                BufferedImage image = ImageIO.read(stream);
                if (image == null) throw new IOException("无法解码图标资源：" + resource);
                images.add(image);
            } catch (IOException exception) {
                throw new IllegalStateException("无法加载 CopyWords 图标", exception);
            }
        }
        return List.copyOf(images);
    }

    private void showWindow() {
        hideTrayMenu();
        frame.setExtendedState(windowMaximized ? Frame.MAXIMIZED_BOTH : Frame.NORMAL);
        frame.setVisible(true); frame.toFront(); frame.requestFocus();
    }
    private void setStatus(String message) {
        SwingUtilities.invokeLater(() -> {
            status.setText(message); status.setToolTipText(message);
            if ((message.startsWith("复制失败") || message.startsWith("自动粘贴未完成") || message.contains("请手动"))
                    && tray != null && !frame.isVisible())
                tray.displayMessage("CopyWords", message, TrayIcon.MessageType.WARNING);
        });
    }
    private void showError(String message) { JOptionPane.showMessageDialog(frame, message, "CopyWords", JOptionPane.WARNING_MESSAGE); }

    private void requestExit() {
        if (exiting || afterSave != null) return;
        closingForExit = true;
        flushAutoSave(this::exit);
    }

    private void exit() {
        exiting = true;
        saveTimer.stop();
        clipboard.pause();
        saveWindowState();
        hotkeys.close(); clipboard.close();
        if (tray != null) SystemTray.getSystemTray().remove(tray);
        if (trayMenuOwner != null) trayMenuOwner.dispose();
        frame.dispose();
        try { lock.release(); lockChannel.close(); } catch (Exception exception) { exception.printStackTrace(); }
        System.exit(0);
    }

    public static void main(String[] args) {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            JOptionPane.showMessageDialog(null, "CopyWords 第一版仅支持 Windows"); return;
        }
        SwingUtilities.invokeLater(() -> {
            FileChannel channel = null;
            FileLock instance = null;
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                FontUIResource font = new FontUIResource("Microsoft YaHei UI", Font.PLAIN, 14);
                for (Object key : Collections.list(UIManager.getDefaults().keys()))
                    if (UIManager.get(key) instanceof Font) UIManager.put(key, font);
                Path home = Path.of(System.getProperty("copywords.home",
                        Path.of(System.getenv("APPDATA"), "CopyWords").toString()));
                Files.createDirectories(home);
                channel = FileChannel.open(home.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                instance = channel.tryLock();
                if (instance == null) {
                    channel.close();
                    JOptionPane.showMessageDialog(null, "CopyWords 已在运行，请从系统托盘打开设置。", "CopyWords", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                Files.createDirectories(home.resolve("logs"));
                System.setErr(new java.io.PrintStream(Files.newOutputStream(home.resolve("logs/application.log"),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND), true, java.nio.charset.StandardCharsets.UTF_8));
                new CopyWords(home, channel, instance);
            } catch (Throwable exception) {
                exception.printStackTrace();
                JOptionPane.showMessageDialog(null, "CopyWords 启动失败：\n" + exception.getMessage(), "CopyWords", JOptionPane.ERROR_MESSAGE);
                try { if (instance != null) instance.release(); if (channel != null) channel.close(); }
                catch (Exception ignored) { }
                System.exit(1);
            }
        });
    }
}
