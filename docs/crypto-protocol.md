# 本地加密与分片协议 v1

## 密钥与随机数

- 每个资源生成一个 32 字节 AES-256 文件密钥。
- 每个资源生成一个 16 字节随机 UUID，用于分片归组。
- 每个数据分片生成独立的 12 字节 AES-GCM IV。
- 加密元数据使用另一个独立的 12 字节 IV。
- 随机值必须来自 Web Crypto `crypto.getRandomValues`。

## 分片

- 默认明文分片大小为 20 MiB（20 × 1024 × 1024）。
- 单资源最多 25 片，即默认最大 500 MiB。
- 空文件仍生成一个 AES-GCM 认证分片。
- AES-GCM tag 长度为 128 bit，包含在密文尾部。

## AAD

数据分片 AAD：

```text
UTF-8 "TMSHARE1:DATA" | 16-byte resource UUID | uint16 part index | uint16 part count
```

元数据 AAD：

```text
UTF-8 "TMSHARE1:META" | 16-byte resource UUID
```

整数使用大端序。AAD 防止密文跨资源或跨分片替换。

## 加密元数据

UTF-8 JSON 在加密前包含：

- `version`：固定为 `1`
- `fileName`
- `mimeType`
- `originalSize`
- `originalSha256`：64 位小写十六进制

## 恢复验证顺序

1. 验证资源 UUID、分片总数、序号范围、缺片与重复。
2. 按分片序号排序。
3. 验证每片密文 SHA-256。
4. 使用绑定 AAD 执行 AES-GCM 认证解密。
5. 增量计算恢复后原文件 SHA-256 和总大小。
6. 与加密元数据严格比较；不一致时不得输出文件。
