# Android 差分更新

当前实现仅用于官网、网盘或其他侧载渠道，不用于 Google Play 版本。Android 最终仍会显示系统安装确认界面。

## 存储分工

- uniCloud 内置存储：只保存增量补丁。
- 腾讯 COS：保存增量补丁和最新版完整 APK（对象名使用 `.bin`，下载后校验并以 `.apk` 安装）。
- 阿里云 OSS：不参与更新发布。
- 客户端优先下载与当前版本匹配的补丁；补丁不存在、下载失败、合成失败或校验失败时，允许改用 COS 完整包。
- 数据库保存对象引用和 SHA-256，不保存永久公开地址；每次检查更新时生成短期下载地址。

## 云函数响应格式

原有 `tm-version` 响应继续保留，并增加可选 `patches`：

```json
{
  "versionCode": 5,
  "versionName": "2.3-native",
  "notes": "修复问题",
  "downloadUrl": "https://example.com/full-download-page",
  "force": false,
  "patches": [
    {
      "fromVersionCode": 4,
      "url": "https://example.com/4-to-5.tmdelta.gz",
      "size": 1234567,
      "sha256": "64位十六进制补丁哈希",
      "baseApkSha256": "64位十六进制旧APK哈希",
      "resultApkSha256": "64位十六进制新APK哈希"
    }
  ]
}
```

没有当前版本对应的补丁时，客户端继续显示“去网盘更新”。补丁下载、合成或校验失败时，用户可选择“完整更新”。

## 生成补丁

必须使用已经实际发布的旧 APK，不可临时重新构建旧版本，否则基础哈希可能不同。

```powershell
python tools/make_delta.py old.apk new.apk 4-to-5.tmdelta.gz --from-version 4 --url https://example.com/4-to-5.tmdelta.gz
```

脚本会重新应用补丁并逐字节验证结果，然后输出可直接放入 `patches` 数组的 JSON。

如果补丁达到完整 APK 的 60% 以上，建议不发布该补丁，只保留完整更新。

## 统一发布

复制后端 `share-api/tools/release-manifest.example.json`，填写新版本、完整 APK、补丁路径和旧 APK 哈希，然后运行：

```powershell
node tools/publish-update.js tools/release-manifest.json
```

发布工具会自动计算新 APK 和补丁哈希，通过临时 COS 对象把文件交给云函数，云函数再按既定分工保存：完整包仅 COS，补丁同时保存到 uniCloud 和 COS。发布成功或失败后，临时对象都会被删除。

## 安全检查

客户端依次验证旧 APK 哈希、补丁大小与哈希、补丁头中的双重哈希、目标 APK 哈希、包名、版本号以及签名证书。任何一步失败都会删除未完成的新 APK，且不会启动系统安装器。
