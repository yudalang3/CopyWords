# CopyWords

用 Java 25 和 Swing 编写的 Windows 常用文本快捷复制工具。

## 启动与使用

双击项目根目录中的 **CopyWords 快捷方式（CopyWords.lnk）** 即可启动，无控制台窗口。当前已构建的程序位于 `dist/CopyWords.jar`，使用本机现有 Java 25，不需要另装运行环境。

1. 在文本框中输入常用词语、短句或邮箱。文本为单行，中文和首尾空格都会原样保留。
2. 点击左侧快捷键按钮，然后按住 Caps／Ctrl／Shift／Alt，再按字母、数字或 F1–F11，点击“确定”。也可清除快捷键或取消设置。
3. 点击“+ 添加”增加条目，点击每行“删除”移除条目。
4. 点击“保存”让所有修改生效。保存失败会标出出错的行并说明原因，上次生效的内容仍可使用。
5. 在任何其他应用中按对应快捷键，将文本复制到剪贴板，再按 Ctrl+V 粘贴。

默认有五行：Caps+1 对应 AI4S，Caps+2 对应人生价值，Caps+3 对应 xxx@example.com，另外两行空白。新增条目优先使用本应用尚未分配的 Caps+1～9、0。Caps 组合需要按住 Caps，再按数字；松开后按 Ctrl+V 粘贴。独立按 Caps 仍可切换大小写，组合复制不会切换。左右 Alt 都可以用于自定义组合。空文本或没有快捷键的条目不会注册快捷键。

## 托盘和自启动

关闭主窗口后，程序继续在系统托盘运行。双击托盘图标或在右键菜单选择“打开设置”可重新打开窗口；选择“退出”才结束程序并释放快捷键。未保存的修改会在真正退出前提示保存、丢弃或取消。重复启动会提示从托盘打开已有程序。

托盘右键菜单使用 Swing 中文字体绘制，避免本机 Java 25 原生 AWT 菜单显示方框。菜单支持 Escape 关闭，点击其他窗口也会关闭。

窗口的位置、大小和最大化状态会在关闭到托盘或真正退出时自动保存，无需点击“保存”。下次启动或从托盘打开时恢复；即使选择“丢弃并退出”，也会记住窗口状态，但不会保存未确认的文本修改。显示器或分辨率改变时，会将窗口调整到可用屏幕内。没有历史状态时，默认宽度为 760、高度为 1280，并限制高度不超过屏幕可用区域。

“Windows 启动时自动运行”和“启动后最小化到系统托盘”默认勾选。首次启动始终显示窗口，**首次点击保存后才设置自启动**；之后按保存的选项运行。自启动使用当前用户的 Windows Run 项，在登录后启动，无需管理员权限。取消勾选并保存即可移除自启动项。系统托盘不可用时，程序保留窗口运行。

文本和快捷键配置保存在 `%APPDATA%\CopyWords\config.xml`，窗口状态独立保存在同目录的 `window-state.xml`，运行错误日志在其 `logs` 子目录。启动时遇到被其他程序占用的快捷键，会提示对应条目，其余可用条目正常工作。剪贴板临时被占用时会进行三次短暂重试，仍失败则提示重新按快捷键。

修改或移动应用目录后，请重新构建快捷方式；已启用自启动时，再次保存以更新启动路径。直接删除应用前，建议先取消自启动并保存。

## 构建

环境：Windows、JDK 25、Maven。首次构建需要联网下载依赖；依赖缓存、编译及测试中间文件都放在项目 `temp` 下。

在项目目录运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build.ps1
```

脚本运行自动测试并生成 `dist/CopyWords.jar` 和根目录的 `CopyWords.lnk`。构建日志为 `logs/build.log`。也可通过以下命令启动：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run.ps1
```

快捷方式绑定构建时找到的 `java.exe` 同目录下的 `javaw.exe`。若 Java 安装位置变化，重新运行构建脚本即可。

## 测试

普通构建运行十八项自动测试，另有五项 Windows 原生操作测试默认跳过。以下命令运行全部二十三项测试：

```powershell
mvn -B '-Dmaven.repo.local=temp/m2' '-Dcopywords.windowsSmoke=true' test
```

原生测试会临时模拟按键、使用系统剪贴板并创建独立命名的自启动测试项，完成后恢复。测试时请暂时不要操作键盘。

完整界面测试使用独立配置，检查快捷键录入、取消、添加、删除、保存、托盘、重复启动和重启恢复：

```powershell
java --enable-native-access=ALL-UNNAMED -cp dist/CopyWords.jar scripts/UiSmoke.java
java --enable-native-access=ALL-UNNAMED -cp dist/CopyWords.jar scripts/WindowStateExitCheck.java
java --enable-native-access=ALL-UNNAMED '-Dsun.java2d.uiScale=1.5' -cp dist/CopyWords.jar scripts/UiSmoke.java 150percent
java --enable-native-access=ALL-UNNAMED -cp dist/CopyWords.jar scripts/WindowStateExitCheck.java 150percent
```

截图和报告放在 `logs` 下。窗口与托盘修复验收见 `logs/window-state-verification.md`，之前的快捷键验收见 `logs/caps-verification.md`。界面测试还会检查托盘右键事件、菜单 Escape、窗口尺寸与位置、最大化恢复，以及丢弃退出后的配置隔离。

## 项目文件与依赖

Java 源码及测试在 `src`，辅助脚本在 `scripts`，原始附件在 `orig_matrials`，正式 JAR 在 `dist`。程序使用 JDK 自带 XML 配置存储，不依赖数据库或联网服务。

`dist/CopyWords.jar` 是包含依赖的完整 JAR：JNA、jna-platform 及其 Windows 原生 DLL 已一起打包，运行时无需另下载这些库。JAR 不包含 Java 运行环境，仍需要 Java 25；本机启动快捷方式已经指向当前安装的 Java 25。

Windows 原生接口使用 [JNA / JNA Platform 5.19.1](https://github.com/java-native-access/jna)，采用 Apache License 2.0，其许可证随 JAR 保留。Caps 组合通过 Windows 低级键盘监听实现，其余组合使用 [Windows RegisterHotKey](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-registerhotkey)，自启动基于 [当前用户 Run 项](https://learn.microsoft.com/en-us/windows/win32/setupapi/run-and-runonce-registry-keys)。
