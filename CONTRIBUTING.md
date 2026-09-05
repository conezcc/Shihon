# 为 Shihon 做贡献 · Contributing · 貢献

请将 Shihon 的问题和功能建议提交到 [本项目 Issues](https://github.com/conezcc/Shihon/issues)。报告请附版本、设备、Android/SmartOS 版本、复现步骤；墨水屏问题请同时说明阅读方向、缩放模式及刷新设置。

欢迎代码与翻译 PR。运行 `python3 scripts/check_translations.py` 和 `./gradlew spotlessCheck testDebugUnitTest verifySqlDelightMigration`，并对涉及的阅读操作做实机验证。测试只能证明已覆盖的逻辑，请区分代码检查、模拟测试与实机复现。

翻译资源在 `i18n/src/commonMain/moko-resources/`：`base` 为英文、`zh-rCN` 为简体中文、`ja` 为日文。新增文案须同步三语，保留格式占位符并避免硬编码界面文字。README.md 是中文主文档；README.en.md 与 README.ja.md 应保持功能说明一致。Shihon 专属翻译通过本仓库 PR 维护。

签名密钥、密码、个人备份及下载内容不得提交。构建环境与签名流程见 [构建指南](docs/BUILDING.md)。保留第三方版权和许可证，遵守 [行为准则](CODE_OF_CONDUCT.md)。

## English

File Shihon issues in this repository with version, device, firmware and reproduction steps. For reader issues include reading direction, scaling and refresh settings. Code and translation PRs are welcome. Run the translation checker, Spotless, unit tests and migration verification above; validate affected reader behavior on hardware and distinguish tests from device reproduction.

Keep Chinese, English and Japanese resources and READMEs in sync. Preserve placeholders, upstream copyrights and dependency licenses. Never commit signing keys, passwords, personal backups or downloaded content. See the build guide and code of conduct.

## 日本語

本リポジトリへバージョン・端末・ファームウェア・再現手順を添えて報告してください。読書画面の問題には読む方向、拡大設定、リフレッシュ設定も必要です。コードや翻訳の PR を歓迎します。上記の検証を実行し、変更した操作を実機でも確認してください。

中英日のリソースと README を同期し、書式指定子、上流の著作権、依存ライセンスを保持してください。署名鍵・パスワード・個人バックアップ・ダウンロードしたコンテンツはコミットしないでください。
