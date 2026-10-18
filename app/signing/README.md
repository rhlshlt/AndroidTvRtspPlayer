# Phase 0 test signing key

`phase0-test.keystore` signs the `debug` and `perf` builds, so every build, from GitHub Actions or
a PC, carries the same signature and installs over the previous one with `adb install -r`. Without
it, each GitHub Actions runner made its own random debug key, and a new build failed to install
with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

This key is **public**: its passwords are below and it is in the repository, so anyone can sign an
APK with it. That is acceptable while the app keeps camera passwords in memory only (Phase 0).
Before Phase 1 saves camera passwords, replace it with a private key kept out of the repository
(for example in a GitHub Actions secret), because an app signed with the same key can replace this
one and read its data. See P0-20 in [docs/DECISIONS.md](../../docs/DECISIONS.md).

| Item | Value |
| --- | --- |
| Type | PKCS12, RSA 2048, valid until 2056 |
| Alias | `androiddebugkey` |
| Store and key password | `android` |
| Certificate SHA-256 | `44:5F:B9:54:8B:9A:F3:10:5A:7F:8B:B0:D4:2A:0A:97:3D:D9:50:37:DE:B8:9C:08:FC:8B:B3:9A:91:9A:18:35` |

An app installed from a build signed with another key must be removed once before this one
installs: pull its logs first, because uninstalling deletes them.

```powershell
adb pull /sdcard/Android/data/io.github.rhlshlt.tvrtsp/files/metrics D:\Projects\phase0-results-backup
adb uninstall io.github.rhlshlt.tvrtsp
adb install app-perf.apk
```
