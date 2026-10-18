# 隐像 / Yinxiang

隐像是一套 Android 优先的实验性资源分享工具。它将鲁棒盲水印、短 ID 映射、客户端加密、第三方存储适配和轻量后端组合在一起，让一张普通图片成为获取资源的入口。

> [!WARNING]
> 本项目主要由 AI 辅助构建，尚未经过完整的代码、安全、兼容性或性能审计。请把它视为研究原型和实现参考，不要未经审查直接用于生产环境。请只处理你有权使用和传播的内容，并遵守当地法律及第三方平台规则。

[English](#english) · [部署文档](docs/DEPLOYMENT.md) · [安全策略](SECURITY.md) · [参与贡献](CONTRIBUTING.md)

## 工作原理

隐像不会把大文件直接塞进盲水印。典型流程如下：

1. 客户端加密资源，并将大文件保存到用户选择的外部存储。
2. 后端保存必要的资源元数据并生成短 ID。
3. 客户端把短 ID 嵌入分享图片的像素信息。
4. 接收者从图片中提取 ID，再通过自己配置的后端取得资源信息。

盲水印只承担“索引入口”的角色。外部资源是否长期可用，取决于所使用的存储服务和平台规则。

## 主要功能

- Android 端本地 TrustMark/ONNX 推理
- 图片水印嵌入与提取
- 客户端文件加密与完整性校验
- 短 ID 与资源元数据映射
- 本地 Fastify 服务和 uniCloud 云函数两种后端实现
- 可选的第三方存储与网盘适配
- 完整版和仅提取版 Android product flavor

## 仓库结构

```text
android/                 Android、Kotlin、Compose 与原生推理代码
apps/web/                React/Vite Web 界面
apps/server/             本地 Fastify 参考后端
packages/crypto/         加密与哈希逻辑
packages/protocol/       客户端/服务端协议
packages/trustmark/      TrustMark Web 推理封装
backend/unicloud/        uniCloud 云函数后端
docs/                    架构、协议和部署文档
```

## 下载 APK

现成的正式版 v16 APK 将发布在 [GitHub Releases](https://github.com/123qewafdfg/pixel-vault/releases)。请核对 Release 页面提供的 SHA-256。

公开源码不会包含维护者的后端地址。需要自行构建时，请部署自己的后端并在构建时传入地址；Release 中的预构建 APK 按正式版原样提供。

首个公开构建使用 Android Debug 证书签名，适合安装、测试和复现，不应被视为长期稳定的生产签名。后续如切换正式签名，Android 可能要求卸载旧版本后再安装。

为避免公开第三方抓包凭据，公开版默认不配置迅雷 Client ID/Secret，因此对应集成功能不可用。使用者只能在确认自己有权使用相关凭据后，通过本地 Gradle 参数自行构建。

## 快速开始

### 环境要求

- Node.js 24 或更高版本
- pnpm 11.5.1
- JDK 17
- Android SDK
- Android NDK 30.0.16248370
- CMake 4.1.2

### 安装与检查

```powershell
pnpm install --frozen-lockfile
Copy-Item .env.example .env.local
pnpm test
pnpm build
```

### 配置后端地址并构建 Android

```powershell
pnpm android:sync
Set-Location android
.\gradlew.bat assembleFullRelease -PapiBaseUrl=https://your-api.example
```

输出文件通常位于：

```text
android/app/build/outputs/apk/full/release/app-full-release.apk
```

若不传入 `apiBaseUrl`，应用会使用不可路由的 `https://example.invalid`，避免意外连接维护者服务。

完整部署方法见 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)。

## 安全与隐私

- 不要提交 `.env`、`storage.keys.json`、数据库、日志、签名文件或云服务凭据。
- 不要在公开 APK 中硬编码私有后端地址或密钥。
- 客户端加密不能保证外部文件本身安全；下载和安装未知文件前请自行验证。
- 第三方存储、网盘和内容平台可能更改接口、删除内容或限制账号。
- 发现漏洞时请按照 [SECURITY.md](SECURITY.md) 私下报告，不要在公开 Issue 中放置凭据或可直接利用的敏感细节。

## 项目状态

- 当前 Android 版本：`versionCode 16` / `versionName 3.3-native`
- 主要目标平台：Android arm64-v8a
- Windows、iOS 和完整浏览器版本尚未作为正式发行目标
- 没有完成设备矩阵测试，不保证所有设备兼容

## 许可证

本仓库作为组合项目依据 [GNU AGPL-3.0](LICENSE) 发布，允许商业使用、复制、修改和分发，但必须遵守 AGPL-3.0 的源码公开和许可证保留要求。

项目包含不同许可证的第三方组件，详见 [NOTICE](NOTICE)、各子目录中的许可证文件以及 `packages/trustmark/THIRD_PARTY_NOTICES.md`。

---

## English

Yinxiang is an Android-first experimental resource-sharing system that combines robust blind watermarking, short-ID mapping, client-side encryption, external storage adapters, and a lightweight backend. A normal-looking image carries only a compact lookup ID; large payloads remain in storage selected by the user.

> [!WARNING]
> This project was built largely with AI assistance and has not received a comprehensive code, security, compatibility, or performance audit. Treat it as a research prototype and implementation reference. Do not deploy it to production without an independent review. Use it only with content you are authorized to process and distribute.

### How it works

1. The client encrypts a resource and stores large payloads outside the project backend.
2. The backend stores the required metadata and issues a short ID.
3. The client embeds that ID into an ordinary image using a robust watermark.
4. A recipient extracts the ID and queries their own configured backend for the resource metadata.

The watermark is an index, not unlimited image storage. Availability still depends on the selected external storage provider.

### Highlights

- On-device TrustMark/ONNX inference for Android
- Watermark embedding and extraction
- Client-side encryption and integrity verification
- Short-ID resource registry
- Local Fastify and uniCloud backend implementations
- Optional cloud-drive and third-party storage adapters
- Full and extraction-only Android flavors

### Build

Requirements: Node.js 24+, pnpm 11.5.1, JDK 17, Android SDK, NDK 30.0.16248370, and CMake 4.1.2.

```powershell
pnpm install --frozen-lockfile
Copy-Item .env.example .env.local
pnpm test
pnpm android:sync
Set-Location android
.\gradlew.bat assembleFullRelease -PapiBaseUrl=https://your-api.example
```

Without `apiBaseUrl`, the app uses the non-routable placeholder `https://example.invalid`. See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) for backend setup and signing guidance.

The first public APK is signed with an Android debug certificate. It is suitable for installation, testing, and reproducible review, but it is not a long-term production signing identity. A future signing-key change may require uninstalling the old build before installation.

To avoid publishing third-party captured credentials, the public build leaves Xunlei client IDs and secrets empty, so that integration is unavailable by default. Users may supply their own authorized values through local Gradle properties.

### License

The combined repository is licensed under [GNU AGPL-3.0](LICENSE). Commercial use is allowed, subject to the license's source-availability and notice requirements. Third-party components retain their respective licenses; see [NOTICE](NOTICE).
