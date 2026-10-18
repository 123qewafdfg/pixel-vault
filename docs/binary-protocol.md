# 存储载体二进制协议 v1

每个载体由四段连续数据组成：

```text
原始 PNG | AES-GCM 分片密文 | 加密元数据（仅第 0 片） | 112 字节固定索引
```

## 固定索引

所有整数均为无符号大端序。偏移从索引首字节开始。

| 偏移 | 长度 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 8 | magic | ASCII `TMSHARE1` |
| 8 | 2 | protocol_version | `1` |
| 10 | 2 | flags | v1 必须按已知位解释 |
| 12 | 16 | resource_id | 随机资源 UUID |
| 28 | 2 | part_index | 从 `0` 开始 |
| 30 | 2 | part_count | 至少为 `1` |
| 32 | 8 | cipher_length | 包含 AES-GCM tag |
| 40 | 4 | metadata_length | 非第 0 片必须为 `0` |
| 44 | 8 | original_png_length | 必须等于 IEND 结束位置 |
| 52 | 12 | data_iv | 分片 AES-GCM IV |
| 64 | 12 | metadata_iv | 元数据 AES-GCM IV；无元数据时保留 |
| 76 | 32 | cipher_sha256 | 分片密文 SHA-256 |
| 108 | 4 | index_crc32 | 前 108 字节 CRC-32/ISO-HDLC |

## 解析要求

解析器必须从文件末尾读取固定索引，先验证 magic 与 CRC32，再验证版本、分片范围和各段长度。`original_png_length + cipher_length + metadata_length + 112` 必须严格等于文件总长度。

PNG 解析必须从标准 8 字节签名开始逐个读取 chunk，不得通过搜索字节串猜测 IEND。`original_png_length` 必须严格指向零长度 IEND chunk 之后，原 PNG 不允许已有尾随数据。

CRC32 只用于检测索引意外损坏，不提供密码学认证。分片真实性由 AES-256-GCM 和 SHA-256 验证。
