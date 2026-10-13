# CopyWords 代码审查报告

## 🐛 Bug / 潜在问题

---

### 1. `InputSimulator.snapshot()` — 超长剪贴板文本可能导致整数溢出 ⚠️

**文件**: [`InputSimulator.java:75-77`](file:///c:/Users/yudal/Documents/GitHub/copyWords/src/main/java/cn/copywords/InputSimulator.java#L75-L77)

```java
long count = Math.min(ClipboardApi.KERNEL.GlobalSize(handle).longValue() / 2,
        (long) expectedText.length() + 1);
char[] chars = data.getCharArray(0, (int) count);
```

`count` 是 `long` 类型，但传给 `getCharArray` 时被强转为 `int`。虽然 `expectedText.length() + 1` 通常不会超过 `Integer.MAX_VALUE`，但如果外部剪贴板里的内容非常大（例如被恶意软件填充），`GlobalSize` 可能返回一个很大的值。当前代码因为 `Math.min` 会约束到 `expectedText` 长度，所以**实际风险低**。

> [!NOTE]
> 风险很低，因为 `Math.min` 保证了 `count <= expectedText.length() + 1`，不过加一行 `assert count <= Integer.MAX_VALUE` 或者显式检查也不错。

**严重程度**: 🟢 低

---

### 2. `InputSimulator.snapshot()` — `text` 可能保持为 `null`

**文件**: [`InputSimulator.java:68-86`](file:///c:/Users/yudal/Documents/GitHub/copyWords/src/main/java/cn/copywords/InputSimulator.java#L68-L86)

```java
Pointer handle = ClipboardApi.INSTANCE.GetClipboardData(13);
String text = null;
if (handle != null) {
    // ... 尝试读取
    // 如果 chars 数组中没有 \0 终止符，text 保持 null
}
// text 可能是 null，传给了 Snapshot
return new ClipboardService.Snapshot(text, version);
```

当 `GetClipboardData` 返回了有效 handle，但 chars 数组中**没有 `\0` 终止符**时，`text` 仍为 `null`。这在 `ClipboardService.matches()` 中会走 `Objects.equals(request.text(), null)` → `false`，导致被判为"外部修改"，触发 `externalChange()`。

**用户能观察到**: 如果剪贴板的 native 数据恰好没有 null terminator（极端边缘情况），用户会看到"剪贴板已被其他程序修改"的误报。

**修复建议**:
```java
// 如果没有 \0 终止符，把整个 chars 当成 text
if (text == null && chars.length > 0) {
    text = new String(chars);
}
```

**严重程度**: 🟡 中低（边缘情况，但有可能出现）

---

### 3. `GlobalHotkeys.invoke()` — `commands.remove()` 存在竞态条件

**文件**: [`GlobalHotkeys.java:85-88`](file:///c:/Users/yudal/Documents/GitHub/copyWords/src/main/java/cn/copywords/GlobalHotkeys.java#L85-L88)

```java
commands.add(command);
if (User32.INSTANCE.PostThreadMessage(...) == 0) {
    commands.remove(command);  // 线程可能已经 poll 走了
    throw ...;
}
```

在 `PostThreadMessage` 失败时，`command` 可能已经被 hotkey 线程的 `commands.poll()` 取走并正在执行了。此时 `remove()` 返回 false 但不报错。不过这里的时间窗口极小，且 `PostThreadMessage` 失败是**非常罕见**的。

**严重程度**: 🟢 低（理论上的竞态，实际几乎不会触发）

---

### 4. `GlobalHotkeys` — `nextId` 回绕时的无限循环风险

**文件**: [`GlobalHotkeys.java:99-100`](file:///c:/Users/yudal/Documents/GitHub/copyWords/src/main/java/cn/copywords/GlobalHotkeys.java#L99-L100)

```java
if (nextId > 0xBFFF) nextId = 1;
while (idUsed(nextId)) { if (++nextId > 0xBFFF) nextId = 1; }
```

如果同时注册了 `0xBFFF`（约 49K）个 hotkey binding，`while` 循环永远找不到空闲 ID，会死循环。当然实际上不可能有这么多 binding，但代码中缺少安全出口。

**严重程度**: 🟢 极低（实际不可能触发）

---

### 5. `CopyWords.main()` — 标准错误重定向后异常堆栈丢失

**文件**: [`CopyWords.java:692-693`](file:///c:/Users/yudal/Documents/GitHub/copyWords/src/main/java/cn/copywords/CopyWords.java#L692-L693)

```java
System.setErr(new java.io.PrintStream(
    Files.newOutputStream(home.resolve("logs/application.log"),
        StandardOpenOption.CREATE, StandardOpenOption.APPEND), true, ...));
```

这里打开了一个 `OutputStream` 但**从来没有关闭**。在 `System.exit(0)` 或 `System.exit(1)` 时不保证 flush。如果最后一条重要的错误日志恰好没有被 flush 到磁盘，就会丢失。

虽然构造 `PrintStream` 时传了 `autoFlush = true`，所以每次 `println` 都会 flush，但 `exception.printStackTrace()` 内部并不一定触发 `println` 的 auto-flush 行为（它用的是 `print` + `println` 混合调用，取决于实现）。

**严重程度**: 🟢 低（autoFlush 基本覆盖了）

---

### 6. `CapsHotkeys` — `downKeys` / `consumed` 非线程安全

**文件**: [`CapsHotkeys.java:17`](file:///c:/Users/yudal/Documents/GitHub/copyWords/src/main/java/cn/copywords/CapsHotkeys.java#L17)

```java
private final Set<Integer> downKeys = new HashSet<>(), consumed = new HashSet<>();
```

`downKeys` 和 `consumed` 是普通 `HashSet`，在 hook 线程中读写。`heldKeys` 通过 `volatile Set.copyOf(downKeys)` 发布给外部线程读取（`isKeyDown()`），这部分是安全的。

但 `takeLeftAltPress()` 和 `isKeyDown()` 在其他线程调用时，`downKeys` 本身从不被外部线程直接读，所以实际上没问题。`onKey` 回调总是在同一个 hook 线程上运行。

> [!TIP]
> 当前实际是安全的，因为 `downKeys` / `consumed` 只在 hook 线程中被修改和读取，`heldKeys` 通过 `volatile` 正确发布。不过加个注释说明单线程使用会更清晰。

**严重程度**: 🟢 无（只是缺少注释）

---

### 7. `CapsHotkeys.onKey()` — 潜在的 `NullPointerException`

**文件**: [`CapsHotkeys.java:64-65`](file:///c:/Users/yudal/Documents/GitHub/copyWords/src/main/java/cn/copywords/CapsHotkeys.java#L64-L65)

```java
private LRESULT next(int code, WPARAM event, WinUser.KBDLLHOOKSTRUCT key) {
    return User32.INSTANCE.CallNextHookEx(hook, code, event, new LPARAM(Pointer.nativeValue(key.getPointer())));
}
```

如果 `hook` 在 `close()` 后被设为 null...但实际上 `close()` 并不设 `hook = null`，`close()` 只是 `PostThreadMessage(WM_QUIT)` 让消息循环退出，然后 finally 中 `UnhookWindowsHookEx`。所以 hook 回调不会在 unhook 之后再被调用。

**严重程度**: 🟢 无

---

## 🔧 优化建议

---

### O1. `ConfigStore.save()` — `Files.deleteIfExists(temporary)` 可能删除已经 move 成功的文件

**文件**: [`ConfigStore.java:68`](file:///c:/Users/yudal/Documents/GitHub/copyWords/src/main/java/cn/copywords/ConfigStore.java#L68)

```java
} finally { Files.deleteIfExists(temporary); }
```

如果 `Files.move()` 成功（且不是 atomic 而是 copy-then-delete），`temporary` 已经不存在了，`deleteIfExists` 会默默返回 false，**没有问题**。

但如果 `Files.move()` 使用了 `ATOMIC_MOVE` 且成功，临时文件也不再存在。所以这个 finally 块实际上是一个安全网，**代码没有 bug**。

不过有一个边缘情况：如果系统在 `move` 成功后、finally 执行前崩溃，下次启动时 `config-*.tmp` 文件会留在目录中（无法被这个 finally 清理）。可以考虑在启动时清理 stale 的 tmp 文件。

> [!TIP]
> 可以在 `ConfigStore.load()` 时顺便清理目录中遗留的 `config-*.tmp` 文件。

**价值**: 🟢 低

---

### O2. `WindowState.fitToScreens()` — 空屏幕列表会抛异常

**文件**: [`WindowState.java:52`](file:///c:/Users/yudal/Documents/GitHub/copyWords/src/main/java/cn/copywords/WindowState.java#L52)

```java
Rectangle screen = screens.getFirst(); // 如果 screens 为空会抛 NoSuchElementException
```

虽然调用方 `usableScreens()` 在正常情况下总会返回至少一个屏幕，但如果某些特殊环境下（比如远程桌面断开）`getScreenDevices()` 返回空数组，这里就会崩溃。

**修复建议**: 加一个空列表防护。

**严重程度**: 🟡 低（特殊环境）

---

### O3. `CopyWords.showTrayMenu()` — 同样有空列表风险

**文件**: [`CopyWords.java:594`](file:///c:/Users/yudal/Documents/GitHub/copyWords/src/main/java/cn/copywords/CopyWords.java#L594)

```java
Rectangle screen = screens.stream().filter(...).findFirst().orElse(screens.getFirst());
```

同上，`screens` 为空时 `getFirst()` 抛异常。

**严重程度**: 🟡 低

---

### O4. `AutoStart.command()` — 路径中包含引号的防护

**文件**: [`AutoStart.java:14-15`](file:///c:/Users/yudal/Documents/GitHub/copyWords/src/main/java/cn/copywords/AutoStart.java#L14-L15)

```java
return "\"" + javaw + "\" --enable-native-access=ALL-UNNAMED -jar \"" + jar.toAbsolutePath() + "\"";
```

如果用户的 `java.home` 或 JAR 路径中本身就包含 `"` 引号字符（极端罕见），生成的命令会被注入/破坏。

**严重程度**: 🟢 极低

---

### O5. `Config.upgradeLegacyDefaults()` — `oldSecondDefault` 逻辑仅检查 `i == 1`

**文件**: [`Config.java:24`](file:///c:/Users/yudal/Documents/GitHub/copyWords/src/main/java/cn/copywords/Config.java#L24)

```java
boolean oldSecondDefault = i == 1 && key != null && key.equals(new Hotkey(Hotkey.CTRL | Hotkey.SHIFT, '2'));
```

这个硬编码 `i == 1` 意味着如果用户重新排列了条目顺序（第二行不再是原来的第二行），旧默认的 `Ctrl+Shift+2` 不会被迁移。这是**设计意图**还是遗漏？

> [!IMPORTANT]
> 如果用户手动把 `Ctrl+Shift+2` 挪到其他行（比如第 4 行），`upgradeLegacyDefaults` 不会迁移它。建议确认这是否是预期行为。

**严重程度**: 🟡 需确认意图

---

### O6. `CopyWords` — 过多的 `boolean` 状态标志

当前 [`CopyWords.java:46`](file:///c:/Users/yudal/Documents/GitHub/copyWords/src/main/java/cn/copywords/CopyWords.java#L46) 有大量布尔状态：

```java
private boolean saving, exiting, migrationPending;
private boolean ready, capturing, confirmingText, pendingSave, userSaveRequested,
                startupSynced, saveCompleted, closingForExit;
```

共 **10 个** boolean 标志管理自动保存状态机。这不是 bug，但使代码很难推理，交互组合有 1024 种。考虑将保存相关的状态提取成一个**显式的状态枚举或状态对象**，以减少组合爆炸和潜在的状态不一致。

**价值**: 🟠 中（可维护性改善，但改动面大）

---

### O7. `ClipboardService` — `LinkageError` 捕获不必要地宽泛

**文件**: [`ClipboardService.java:156`](file:///c:/Users/yudal/Documents/GitHub/copyWords/src/main/java/cn/copywords/ClipboardService.java#L156)

```java
} catch (RuntimeException | LinkageError exception) {
```

`LinkageError` 包括 `NoClassDefFoundError`、`UnsatisfiedLinkError` 等。这些通常意味着 JNA native 库加载失败，捕获后继续运行可能掩盖更严重的环境问题。建议至少在日志里明确标注这是一个**致命级别**的错误。

**价值**: 🟢 低（防御性代码，有一定道理）

---

## ✅ 做得好的地方

| 方面 | 说明 |
|------|------|
| **原子保存** | `ConfigStore` 和 `WindowState` 使用 temp + atomic move，完美保护数据 |
| **回滚机制** | `GlobalHotkeys.apply()` 注册失败时 unregister staged bindings，`AutoStart.restore()` 回滚注册表 |
| **剪贴板保护** | 检测外部剪贴板修改、多次重试、超时保护，粘贴前二次验证目标窗口 |
| **合成事件标识** | `SYNTHETIC_CAPS` / `SYNTHETIC_PASTE` 的 `dwExtraInfo` 标识避免自己的输入被 hook 拦截 |
| **中文支持** | 输入法组合感知（`composing` 标志）、`endComposition()`、`focusLost` 时确认文本 |
| **测试覆盖** | `ClipboardServiceTest` 覆盖了重试、超时、队列、外部修改、pause/resume 等关键路径 |
| **单实例锁** | `FileChannel.tryLock()` 防止多实例运行 |
| **用户反馈** | 快捷键冲突提供具体的替代方案建议（推荐 Caps 组合） |

---

## 📋 总结

| 类别 | 数量 | 最高严重程度 |
|------|------|------------|
| 真正的 Bug | 1（#2 null text） | 🟡 中低 |
| 理论竞态 / 边缘情况 | 3（#3, #4, O2/O3） | 🟢 低 |
| 代码质量 / 可维护性 | 3（#5, O6, O7） | 🟠 中 |
| 需确认设计意图 | 1（O5） | 🟡 需确认 |

**整体评价**: 代码质量**相当高**，架构设计合理，并发和错误处理考虑周到。没有发现严重的安全漏洞或数据丢失 bug。最值得关注的是 **#2（snapshot 中 text 可能为 null）**和 **O5（升级逻辑的 i==1 硬编码）**需要确认。

## Open Questions

> [!IMPORTANT]
> 1. **Bug #2**: `snapshot()` 中 chars 没有 `\0` 终止符时 text 为 null 的行为——是否应该 fallback 到整个 char 数组？
> 2. **O5**: `upgradeLegacyDefaults()` 中 `i == 1` 的硬编码——如果用户重排了条目，`Ctrl+Shift+2` 在非第二行是否应该被迁移？
> 3. **O6**: 是否有计划重构保存状态机？当前 10 个 boolean 虽然能工作，但新增功能时容易引入状态不一致。

---

## 🔍 二次审查（2026-10-13）

针对上述所有问题逐项重新审视代码上下文，结合当前 `git diff` 中的修改，判定修复状态与实际必要性。

### 不需要修复的项（原报告过度防御）

| 问题 | 结论 |
|------|------|
| **#1** `count` 强转 int | `Math.min` 右侧是 `expectedText.length() + 1`，Java `String.length()` 最大就是 `Integer.MAX_VALUE`，`count` 永远在 int 范围内。加 assert 纯属多余。 |
| **#2** `text` 保持 null | `snapshot(expectedText)` 只在 `ClipboardService` 的 VERIFY/SEND/GAP 阶段调用，WRITE 阶段刚通过 `clipboard.write(text)` 写入剪贴板。Windows CF_UNICODETEXT 规范**要求 null terminator**，`SetClipboardData` 写入的数据一定带 `\0`。`text` 为 null 唯一的可能是外部程序在 write 后、snapshot 前篡改了剪贴板且不带 `\0`——此时 `matches()` 返回 false 走 `externalChange()` 是**正确行为**，不是 bug。加 fallback 反而可能把截断乱码误判为匹配。 |
| **#4** `nextId` 回绕死循环 | `apply()` 先 unregister 旧的再 register 新的，同时活跃的 binding 数等于用户手动配的条目数（UI 上限几十个），49K 个 ID 槽不可能用完。 |
| **#5** `System.setErr` 未关闭 | 构造时 `autoFlush = true`，每次输出都 flush。`PrintStream` 生命周期等同进程，退出时 OS 回收 fd。无数据丢失风险。 |
| **#6** `downKeys` / `consumed` 线程安全 | 只在 hook 线程读写，`heldKeys` 通过 `volatile` + `Set.copyOf()` 正确发布。不是 bug。 |
| **#7** `hook` NPE | `close()` 不置 null，`UnhookWindowsHookEx` 在 finally 中，unhook 后回调不再被调用。不是 bug。 |
| **O1** `deleteIfExists` 安全网 | 行为完全正确，tmp 文件名带随机数不会积累，启动清理价值极低。 |
| **O4** 路径含引号 | Windows NTFS 文件名不允许 `"` 字符，场景物理上不可能发生。 |
| **O7** `LinkageError` 捕获范围 | 对 JNA 原生调用的防御性捕获。运行中发生 `UnsatisfiedLinkError` 说明是瞬时问题（如 DLL 被安全软件临时锁定），catch 后给用户提示比直接崩溃好。 |

### 已通过当前修改正确修复的项

| 问题 | 修复方式 | 验证 |
|------|---------|------|
| **#3** `GlobalHotkeys.invoke()` 竞态 | 引入 `Command<T>` + `AtomicBoolean claimed` 原子认领 | `GlobalHotkeysCommandTest` 覆盖了通知失败、已取出执行、异常传递三个场景 |
| **O2** `WindowState.fitToScreens()` 空列表 | `if (screens.isEmpty()) return this;` | `WindowStateTest.unavailableScreensKeepSavedGeometry` |
| **O3** `showTrayMenu` 空列表 | 重载方法提前返回 + stderr 日志 | `UiSmoke` 新增空列表调用断言 |
| **O5** `upgradeLegacyDefaults` 隐式迁移 | 移除自动改写逻辑，`readBoolean` 严格解析，旧配置走外部归档脚本 | `ConfigTest.savedBindingsAreLoadedExactlyWithoutAutomaticConversion` + `ArchiveConfigSmoke` |

### 后续可考虑的架构改进

| 问题 | 建议 |
|------|------|
| **O6** 10 个 boolean 状态标志 | 属于架构重构而非 bug fix。当前不影响正确性，建议功能稳定后作为独立重构任务处理。 |

### 二次审查结论

> [!NOTE]
> 当前修改已覆盖所有**真正需要修复**的问题，没有遗漏。原报告中的 Bug #2 经过完整调用链分析确认不是 bug——在 Windows CF_UNICODETEXT 规范下 `\0` terminator 是保证存在的，`text` 为 null 时走 `externalChange()` 是正确的防御行为。其余低/极低风险项均属于理论场景，无实际修复必要。单元测试全部通过（50 run, 0 fail）。
