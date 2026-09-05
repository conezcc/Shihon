# 构建与发布 · Build and release

## 环境 / Requirements

- JDK 21 or newer is required by Metro; CI uses JDK 21. Bytecode target: 17.
- Android SDK Platform 37 (`platforms;android-37.0` in sdkmanager), Build Tools 37.0.0, NDK 29.0.14206865.
- Set `ANDROID_HOME` or `sdk.dir` in ignored `local.properties`.
- Gradle 9.7.1 is selected by the committed wrapper; Python 3 validates translations.

```bash
python3 scripts/check_translations.py
./gradlew spotlessCheck testDebugUnitTest verifySqlDelightMigration
./gradlew assembleDebug
./gradlew assembleRelease
```

Windows: use `python` and `gradlew.bat`.

Debug uses `app.shihon.dev` and the Android debug certificate. Release uses `app.shihon`, version `1.0.0`, version code `2`, R8 shrinking and arm64-v8a. Release output: `app/build/outputs/apk/release/`. Without release signing settings, APKs are unsigned; there is no fallback to debug signing.

默认不添加 `-Pinclude-telemetry` 或 `-Penable-updater`。正式包关闭遥测与应用内更新检查，下载更新请使用本仓库 Releases。发布版本及安装要求见各语言 README。

## 本地签名 / Local signing

Keep the release keystore outside the repository and back it up privately. Create an ignored `keystore.properties` in the repository root:

```properties
storeFile=C:/private/path/shihon-release.jks
storePassword=YOUR_PRIVATE_PASSWORD
keyAlias=shihon
keyPassword=YOUR_PRIVATE_PASSWORD
```

Alternatively set these environment variables (they take precedence):

- `SHIHON_KEYSTORE_PATH`
- `SHIHON_STORE_PASSWORD`
- `SHIHON_KEY_ALIAS`
- `SHIHON_KEY_PASSWORD`

Run `assembleRelease`. Verify the result with Android Build Tools `apksigner verify --verbose --print-certs <apk>`. Preserve the key for future upgrades.

## GitHub Actions

**Build & Test** runs on `main` pushes, all PRs and manual dispatches. It checks all three release languages, formatting, unit tests and SQLDelight migrations, and uploads an unsigned Release APK, reports and R8 mappings. PRs do not receive signing secrets.

For **Release**, set these repository Actions secrets:

| Secret | Value |
| --- | --- |
| `SHIHON_SIGNING_KEY` | Base64-encoded release keystore |
| `SHIHON_STORE_PASSWORD` | Keystore password |
| `SHIHON_KEY_ALIAS` | Signing alias |
| `SHIHON_KEY_PASSWORD` | Key password |

Push a tag matching `versionName` (currently `v1.0.0`). The workflow checks the tag, runs verification, signs the APK, verifies its certificate, creates SHA-256 checksums and creates a **draft** GitHub Release. Review that draft before publishing. The temporary keystore is removed even on failure. Only the release job receives `contents: write` permission.

For a retry, select the existing tag in the manual Release workflow. Existing draft assets can be replaced; a published release is not overwritten automatically.

## 日本語

上記の環境を用意し、翻訳・形式・テスト・移行を検証してからビルドします。署名鍵はリポジトリ外に保管し、`keystore.properties` または環境変数で指定してください。鍵を設定しない Release ビルドは未署名です。

GitHub では通常のビルドと PR に署名鍵を渡しません。4 個のシークレットを設定し、バージョンと一致するタグを push すると、署名済み APK、SHA-256、Release 下書きを生成します。公開は下書きを確認してから行ってください。既に公開したリリースは自動で上書きしません。
