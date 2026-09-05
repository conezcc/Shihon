<div align="center">
<img src=".github/assets/shihon.svg" alt="Shihon" width="88" />

# Shihon · 紙本

[简体中文](README.md) · [English](README.en.md) · **日本語**

[![Build](https://github.com/conezcc/Shihon/actions/workflows/build.yml/badge.svg)](https://github.com/conezcc/Shihon/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/conezcc/Shihon)](https://github.com/conezcc/Shihon/releases)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

</div>

Shihon（紙本）は [Mihon](https://github.com/mihonapp/mihon) をベースにした Android 漫画リーダーです。波紋エフェクト、オフライン文字強調、大きな画像の領域移動などを追加しています。UI は中国語・英語・日本語に対応しています。

## ダウンロードとインストール

[Releases](https://github.com/conezcc/Shihon/releases) から **Shihon 1.0.0** の `Shihon-v1.0.0-arm64-v8a-release.apk` をダウンロードしてください。

- **動作環境**：Android 8.0（API 26）以降、64 ビット ARM（arm64-v8a）。
- **インストールと更新**：APK をインストールします。以降の正式版も同じ署名を使用するため、上書き更新できます。
- **整合性の確認**：リリースには `SHA256SUMS.txt` が付属します。

## Shihon の追加機能

### 波紋エフェクト

対応するシステム機能を持つ iReader SmartOS 4 以降の端末で、ネイティブのページ切り替えを使用します。速度は低速・標準・高速から選択でき、読む方向と画面の回転に応じて切り替わります。設定項目は対応端末でのみ表示されます。

### 画像調整と文字強調

- 画像の明るさ・コントラスト・ガンマを個別に調整できます。
- 端末内の PP-OCRv5 モデルで文字領域を検出し、線を濃くします。強度は調整できます。
- モデルを同梱しており、ページ画像を送信しません。文字認識や翻訳は行いません。

### チャプターの前処理

文字強調用のデータをあらかじめ生成し、読書中に再利用します。前処理キューはダウンロードキューから独立しており、以下の操作に対応します。

- すべて・未読・ブックマーク・選択したチャプターの処理。
- 進捗の確認、一時停止、再開、キャンセル、失敗した処理の再試行。
- ダウンロード完了時や読み始めたときの自動処理。
- 1～8 スレッドでの処理。初期値は 2 です。
- 生成済みデータの削除。

「その他 → 設定 → リーダー → 画像処理」で生成済みデータの使用を有効にし、必要に応じて自動前処理を設定して、文字強調の強度を調整してください。生成データには追加の保存容量が必要です。処理が終わる前でも元画像を読めます。

### ページ単位のリーダー設定

| 機能 | 説明 |
| --- | --- |
| 拡大前の横長ページのプレビュー | 画像全体を表示してから拡大します。表示時間は 0～3 秒、0.1 秒刻み、初期値は 1.2 秒。前のページに戻る際は省略します。 |
| 領域移動 | タップやキー操作で画像内の各領域を表示してから、次のページへ進みます。逆方向への領域移動も個別に有効化でき、なめらかな移動と瞬時の切り替えを選べます。 |
| 現在のズーム範囲でトリミング | 現在の拡大位置をトリミング範囲として保存します。 |
| ページの余白 | 横・縦の余白を個別に調整します。 |
| スワイプによるページ送りの無効化 | スワイプによるページ送りを無効にしても、ピンチズームは使用できます。 |

これらの設定は標準のページ単位のリーダーで使用できます。

## ビルドと開発への参加

**JDK 21**、Android SDK、同梱の Gradle Wrapper を使用します。詳しくは[ビルドとリリースの手順](docs/BUILDING.md)をご覧ください。

GitHub Actions は `main` への push とプルリクエストで、3 言語のリソース、コード形式、単体テスト、データベース移行を検証し、Release APK を生成します。`v1.0.0` などのバージョンタグでは、署名付きビルド、署名検証、SHA-256 ファイル、リリース下書きを作成します。

[不具合報告・提案](https://github.com/conezcc/Shihon/issues)、コードや翻訳の貢献を歓迎します。[貢献ガイド](CONTRIBUTING.md)、[行動規範](CODE_OF_CONDUCT.md)、[プライバシー](docs/PRIVACY.md)もご覧ください。

## ライセンス

[Apache License 2.0](LICENSE)。著作権・第三者に関する表示は [NOTICE](NOTICE)、文字検出モデルの表示は[モデルライセンス](app/src/main/assets/text_enhancement/NOTICE.txt)をご覧ください。
