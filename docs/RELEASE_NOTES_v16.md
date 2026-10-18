# 隐像 v16 公开版 / Yinxiang v16 Public Build

这是项目现成的正式版 v16 APK，按原始构建产物发布。

This release provides the existing official v16 APK as originally built.

## 校验 / Verification

- Package: `com.trustmark.share`
- Version code: `16`
- Version name: `3.3-native`
- ABI: `arm64-v8a`
- APK SHA-256: `62546FAA420D3AF3EDEA6D657659C20A437C719D1F5903B235220A0B930F02EB`
- APK Signature Scheme v2: verified

## 重要说明 / Important notes

- 公开源码已移除维护者的私密后端配置；预构建 APK 按正式版原样提供。
- 自行构建时，在线资源映射功能需要使用者部署自己的后端并传入配置。
- 预构建 APK 不保证能由公开仓库中的默认配置逐字节复现。
- 迅雷 Client ID/Secret 已从公开源码中移除，对应集成在自行构建时默认不可用。
- 项目未经完整安全和设备兼容性审计。

- The public source tree excludes the maintainer's private backend configuration; the prebuilt APK is provided as-is from the official build.
- Self-built APKs require users to deploy and configure their own backend for online registry features.
- The prebuilt APK is not guaranteed to be byte-for-byte reproducible from the repository's default public configuration.
- Xunlei client IDs/secrets were removed from the public source; that integration is unavailable by default in self-built APKs.
- The project has not received a comprehensive security or device-compatibility audit.
