<div align="center">
<img src=".github/assets/shihon.svg" alt="Shihon" width="88" />

# Shihon

[简体中文](README.md) · **English** · [日本語](README.ja.md)

[![Build](https://github.com/conezcc/Shihon/actions/workflows/build.yml/badge.svg)](https://github.com/conezcc/Shihon/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/conezcc/Shihon)](https://github.com/conezcc/Shihon/releases)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

</div>

Shihon (紙本) is an Android manga reader based on [Mihon](https://github.com/mihonapp/mihon). It adds water-ripple page turns, offline text enhancement and region navigation for large images. The interface supports Chinese, English and Japanese.

## Download and install

Download **Shihon 1.0.0** from [Releases](https://github.com/conezcc/Shihon/releases): `Shihon-v1.0.0-arm64-v8a-release.apk`.

- **Requirements**: Android 8.0 (API 26) or later, 64-bit ARM (arm64-v8a).
- **Installation and updates**: install the APK. Subsequent official releases use the same signing certificate and support in-place updates.
- **Integrity**: release assets include `SHA256SUMS.txt`.

## Shihon additions

### Water-ripple page turns

Uses the native page-turn effect on iReader SmartOS 4+ devices with the required system interface. Choose slow, standard or fast. The effect follows reading direction and screen rotation. The setting appears only on supported devices.

### Image adjustments and text enhancement

- Adjust image brightness, contrast and gamma separately.
- Detect text regions with the on-device PP-OCRv5 model and darken strokes with adjustable strength.
- The model is bundled with the app. Pages are not uploaded, and text is neither recognized nor translated.

### Chapter preprocessing

Preprocessing generates text enhancement data for reuse while reading. Its queue is separate from the download queue and supports:

- Processing all, unread, bookmarked or selected chapters.
- Progress display, pause, resume, cancellation and retry of failed tasks.
- Automatic processing when a download finishes or reading starts.
- 1–8 processing threads, with 2 by default.
- Removal of generated data.

Open More → Settings → Reader → Image processing, enable “Use build data”, optionally enable automatic chapter processing, then adjust text enhancement. Generated data uses additional storage. Original images remain readable before processing finishes.

### Paged reader settings

| Feature | Description |
| --- | --- |
| Wide-page preview before zoom | Show the full image before zooming in. Set the delay from 0–3 seconds in 0.1-second steps; default 1.2 seconds. Returning to a previous page skips the preview. |
| Region navigation | Taps and keys move through regions of the image before turning the page. Reverse region navigation can be enabled separately. Choose smooth movement or instant switching. |
| Crop from current zoom | Save the current zoom position as the crop area. |
| Page padding | Adjust horizontal and vertical padding separately. |
| Disable swipe turns | Turn off swipe navigation while keeping pinch zoom. |

These settings apply to the default paged reader.

## Build and contribute

Use **JDK 21**, the Android SDK and the included Gradle Wrapper. See the [build and release guide](docs/BUILDING.md).

GitHub Actions checks the three languages, formatting, unit tests and database migrations on `main` pushes and pull requests, then builds a Release APK. Version tags such as `v1.0.0` trigger signing, certificate verification, SHA-256 checksums and a release draft.

Submit [issues and suggestions](https://github.com/conezcc/Shihon/issues), or contribute code and translations. See [Contributing](CONTRIBUTING.md), the [Code of Conduct](CODE_OF_CONDUCT.md) and [Privacy](docs/PRIVACY.md).

## License

[Apache License 2.0](LICENSE). Copyright and third-party notices are in [NOTICE](NOTICE); see the [model license](app/src/main/assets/text_enhancement/NOTICE.txt) for the text detector.
