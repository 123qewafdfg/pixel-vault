# TrustMark Share 工程边界

## 工作区

- `apps/web`：浏览器客户端。用户文件的加密、分片、链接验证、TrustMark 嵌入与提取均在此执行。
- `apps/server`：索引 API。只保存资源 ID、受保护的文件密钥、加密元数据和第三方链接。
- `packages/protocol`：二进制载体格式、API 类型、协议常量和测试向量。
- `packages/crypto`：AES-256-GCM、SHA-256、分片和重组算法。
- `packages/trustmark`：4 字节资源 ID 与 TrustMark BCH_SUPER 数据层、ONNX 推理适配。

## 不可跨越的边界

1. 原始文件、加密分片正文和恢复后的文件不得发送到 `apps/server`。
2. `apps/server` 不主动请求用户提交的第三方 URL。
3. 文件密钥不得写入 URL、日志或浏览器 LocalStorage。
4. 所有真实算法必须先有固定测试向量，再接入 UI。
5. UI 不得把模拟结果显示为真实成功结果。
