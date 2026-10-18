# 部署与构建 / Deployment and Build

## 1. 配置原则 / Configuration principles

公开仓库不提供维护者的生产后端、云空间 ID、对象存储密钥或 Android 签名材料。所有部署都应使用你自己的账号和端点。

The public repository does not include the maintainer's production endpoint, cloud-space ID, object-storage credentials, or Android signing material. Use infrastructure that you control.

## 2. 本地后端 / Local backend

复制根目录 `.env.example` 为 `.env.local`，至少修改 `TRUSTMARK_MASTER_KEY`。该值应由密码学安全随机源生成，并且不少于 32 字节。

Copy `.env.example` to `.env.local` and replace at least `TRUSTMARK_MASTER_KEY` with 32 or more cryptographically random bytes.

```powershell
pnpm install --frozen-lockfile
pnpm dev:server
```

默认开发地址为 `http://127.0.0.1:8787`。公开部署时应置于 HTTPS 反向代理之后，限制允许来源，并配置请求速率限制。

The development default is `http://127.0.0.1:8787`. Put public deployments behind HTTPS, restrict allowed origins, and enable appropriate rate limits.

## 3. uniCloud 后端 / uniCloud backend

后端工程位于 `backend/unicloud`。在 HBuilderX 或 uniCloud 控制台中创建你自己的服务空间，然后关联项目并上传：

- `uniCloud-alipay/cloudfunctions/share-api`
- `uniCloud-alipay/database`

将 `storage.keys.example.json` 复制为 `storage.keys.json`，填写自己的存储参数和随机 `adminSecret`。`storage.keys.json` 已被 Git 忽略，禁止提交。

Create your own uniCloud service space, associate `backend/unicloud`, and deploy the `share-api` cloud function plus database definitions. Copy `storage.keys.example.json` to `storage.keys.json`, fill in your own provider settings and a random `adminSecret`, and never commit that file.

运行更新发布工具前，还必须通过进程环境设置自己的云函数地址：

```powershell
$env:UPDATE_ENDPOINT='https://your-unicloud-endpoint.example'
node .\tools\publish-update.js .\tools\release-manifest.example.json
```

Before using an update publishing tool, set your own cloud-function URL in the `UPDATE_ENDPOINT` process environment variable. The repository contains no maintainer deployment endpoint.

## 4. Web 客户端 / Web client

```powershell
$env:VITE_API_BASE_URL='https://your-api.example'
pnpm dev:web
```

浏览器受到 CORS、文件访问和跨站下载限制。Web 端主要用于开发和验证，不保证与 Android 端具有完全相同的文件处理能力。

Browsers impose CORS, file-access, and cross-origin download restrictions. The web client is primarily for development and validation and may not match the Android app's file-processing capabilities.

## 5. Android 构建 / Android build

在 `android/local.properties` 中配置 Android SDK 路径。该文件已被 Git 忽略。

Configure your Android SDK path in `android/local.properties`; the file is ignored by Git.

```powershell
pnpm android:sync
Set-Location android
.\gradlew.bat assembleFullRelease -PapiBaseUrl=https://your-api.example
```

`apiBaseUrl` 应为你控制的 HTTPS 地址。省略时会使用 `https://example.invalid`，在线映射功能将不可用。

`apiBaseUrl` must be an HTTPS endpoint under your control. When omitted, the app uses `https://example.invalid`, disabling online registry features.

迅雷适配所需的第三方 Client ID/Secret 默认均为空。如你确认有权使用自己的参数，可在本地构建命令中额外传入：

```powershell
.\gradlew.bat assembleFullRelease `
  -PapiBaseUrl=https://your-api.example `
  -PxunleiClientId=YOUR_VALUE `
  -PxunleiClientSecret=YOUR_VALUE `
  -PxunleiAppClientId=YOUR_VALUE `
  -PxunleiAppClientSecret=YOUR_VALUE
```

The Xunlei integration defaults to empty client IDs/secrets. Supply only values that you are authorized to use, and keep them outside Git.

## 6. 签名与发布 / Signing and release

仓库不包含发布签名。正式分发前，请创建并安全保存自己的 keystore，通过本地或 CI 秘密变量配置签名，不要把 keystore 或密码提交到 Git。

No production signing key is included. Create and securely retain your own keystore, configure signing through local or CI secrets, and never commit the keystore or passwords.

为每个 APK 生成 SHA-256：

```powershell
Get-FileHash .\app\build\outputs\apk\full\release\app-full-release.apk -Algorithm SHA256
```

Generate and publish a SHA-256 checksum for every released APK.
