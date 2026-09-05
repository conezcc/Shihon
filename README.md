<div align="center">
<img src=".github/assets/shihon.svg" alt="Shihon" width="88" />

# Shihon · 纸本

**简体中文** · [English](README.en.md) · [日本語](README.ja.md)

[![Build](https://github.com/conezcc/Shihon/actions/workflows/build.yml/badge.svg)](https://github.com/conezcc/Shihon/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/conezcc/Shihon)](https://github.com/conezcc/Shihon/releases)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

</div>

Shihon（紙本）是基于 [Mihon](https://github.com/mihonapp/mihon) 的 Android 漫画阅读器，增加了水波纹翻页、离线文字增强和大图分区阅读等功能。界面支持中文、英文和日文。

## 下载与安装

从 [Releases](https://github.com/conezcc/Shihon/releases) 下载 **Shihon 1.0.0**：`Shihon-v1.0.0-arm64-v8a-release.apk`。

- **系统要求**：Android 8.0（API 26）及以上，64 位 ARM（arm64-v8a）。
- **安装与更新**：安装 APK；后续正式版使用相同发布签名，可覆盖更新。
- **完整性校验**：发布附件提供 `SHA256SUMS.txt`。

## Shihon 新增功能

### 水波纹翻页

在支持相应系统接口的 iReader SmartOS 4 及以上设备上，使用原生水波纹翻页。可选择慢、标准、快三档速度，方向随阅读方向和屏幕旋转调整。设置项仅在支持的设备上显示。

### 图像调整与文字增强

- 分别调整图像亮度、对比度和 Gamma。
- 使用本机 PP-OCRv5 模型检测文字区域并加深笔画，增强强度可调。
- 检测模型随应用提供，处理时无需上传页面。不识别或翻译文字。

### 章节构建

章节构建用于提前生成文字增强数据，阅读时复用。构建队列与下载队列独立，支持：

- 构建全部、未读、书签或选中的章节。
- 查看进度、暂停、继续、取消和重试失败任务。
- 下载完成或开始阅读时自动构建。
- 设置 1–8 个处理线程，默认 2 个。
- 删除已构建数据。

在「更多 → 设置 → 阅读器 → 图像处理」开启「使用构建数据」，按需开启「自动构建章节」，再调整文本增强强度。构建数据占用额外存储空间；未完成构建时仍可阅读原图。

### 分页阅读设置

| 功能 | 说明 |
| --- | --- |
| 横图放大前预览 | 先显示完整横图，再自动放大。停留时间为 0–3 秒，以 0.1 秒调整，默认 1.2 秒；返回上一页时跳过预览。 |
| 分区阅读 | 点击或按键时，在图片内按区域移动，看完整图后再翻页。可独立开启反向分区阅读，并选择平滑移动或瞬间切换。 |
| 按当前缩放范围裁切 | 将当前缩放位置保存为裁切范围。 |
| 页面留白 | 分别调整水平和垂直留白。 |
| 禁用滑动翻页 | 关闭滑动翻页，保留双指缩放。 |

这些设置用于默认分页阅读器。

## 构建与贡献

项目使用 **JDK 21**、Android SDK 和仓库内的 Gradle Wrapper，详细步骤见 [构建与发布指南](docs/BUILDING.md)。

GitHub Actions 会在推送 `main` 或提交 PR 时检查三语资源、代码格式、单元测试和数据库迁移，并生成 Release APK。版本标签（如 `v1.0.0`）触发签名构建、签名验证、SHA-256 校验文件和发布草稿。

欢迎提交 [问题与建议](https://github.com/conezcc/Shihon/issues) 或代码、翻译 PR。参见 [贡献指南](CONTRIBUTING.md)、[行为准则](CODE_OF_CONDUCT.md) 和 [隐私说明](docs/PRIVACY.md)。

## 许可

采用 [Apache License 2.0](LICENSE)。版权与第三方声明见 [NOTICE](NOTICE)；文字检测模型声明见 [模型许可](app/src/main/assets/text_enhancement/NOTICE.txt)。
