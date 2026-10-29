# watch-apps

Galaxy Watch7 (Wear OS) personal apps, all in one repo. 作者：ArchieKUO

| Folder | App | Version |
| --- | --- | --- |
| `compass-watch/` | 指南針高度 (compass + altitude + pressure) | v0.2 |

## How it builds
- Push to `main` -> GitHub Actions builds every module -> the APKs appear on the `apk` branch as `<folder>-v<versionName>.apk` (e.g. `compass-watch-v0.2.apk`).
- Install on the watch: `adb install -r <file>.apk` (first time after a signature change: `adb uninstall <package>` first).

## Adding a new watch app
1. Add a new top-level folder (e.g. `alarm-watch/`) with its own `build.gradle.kts` (copy `compass-watch/build.gradle.kts`, change `namespace` / `applicationId`) and `src/`.
2. Push. `settings.gradle.kts` picks the folder up automatically and the workflow builds it.
