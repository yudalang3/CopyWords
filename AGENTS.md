# Repository Guidelines

## Project Structure & Module Organization

CopyWords uses Windows, Java 25, Swing, and JNA 5.19.1.

- `src/main/java/cn/copywords/`: application code. `CopyWords` manages Swing and tray UI; `GlobalHotkeys` and `CapsHotkeys` handle native keyboard events; `ClipboardService` copies text; configuration classes persist settings.
- `src/test/java/cn/copywords/`: JUnit tests.
- `scripts/`: build, launch, and Java smoke-test helpers.
- `dist/CopyWords.jar`: executable JAR containing JNA dependencies and native libraries; Java itself is not bundled.
- `orig_matrials/`: original attachments; preserve this spelling.
- `logs/`: diagnostic logs, screenshots, and verification reports.
- `temp/`: Maven cache, build output, and isolated test profiles.

## Build, Test, and Development Commands

Use Windows, installed JDK 25, and Maven. Run commands from the repository root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run.ps1
mvn -B '-Dmaven.repo.local=temp/m2' test
mvn -B '-Dmaven.repo.local=temp/m2' '-Dcopywords.windowsSmoke=true' test
```

The build script tests, packages the shaded JAR, and regenerates `CopyWords.lnk` using the current `javaw.exe`. The launch script starts without a console. Maven commands run ordinary tests or opt into Windows native tests.

## Coding Style & Naming Conventions

Use four-space indentation, UTF-8 sources, `UpperCamelCase` classes, `lowerCamelCase` methods/fields, and `UPPER_SNAKE_CASE` constants. Match existing formatting; no formatter or linter is configured.

Avoid unrelated refactoring. State material assumptions; clarify ambiguities affecting correctness. Keep Swing updates on the EDT and native callbacks short; clipboard retries and blocking work belong off the EDT. Preserve Chinese text and whitespace. Write actionable Chinese UI errors.

## Testing Guidelines

Use JUnit Jupiter with `*Test` class names and descriptive behavior-based methods. No numerical coverage threshold is configured; add regression tests for changed behavior, especially configuration rollback and native resource cleanup.

Run GUI checks after building:

```powershell
java --enable-native-access=ALL-UNNAMED -cp dist/CopyWords.jar scripts/UiSmoke.java
java --enable-native-access=ALL-UNNAMED -cp dist/CopyWords.jar scripts/WindowStateExitCheck.java
```

Native/UI tests send keyboard input and touch clipboard or isolated startup entries. Run sequentially without concurrent keyboard use; restore resources afterward. Verify UI changes at 100% and 150% scaling and record results in `logs/`.

## Commit & Pull Request Guidelines

Use concise imperative commit subjects, such as `Fix tray menu Chinese rendering`, and keep each commit focused. PRs should explain the problem, resulting behavior, validation, and limitations. Link applicable issues and include screenshots for visible changes.

## Configuration Safety

User settings live under `%APPDATA%\CopyWords`. Keep text configuration and window state independent, use atomic replacement, and preserve the last working configuration on failure. Test with isolated profiles; never overwrite personal settings during verification.
