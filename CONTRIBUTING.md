# 参与贡献 / Contributing

## 中文

感谢你改进隐像。提交 Pull Request 前请：

1. 确认改动符合 GNU AGPL-3.0，并保留第三方许可证和归属说明。
2. 不要提交 `.env`、`storage.keys.json`、数据库、日志、APK、签名文件或真实服务地址。
3. 运行 `pnpm test`、`pnpm build`，涉及 Android 时再运行对应 Gradle 测试或构建。
4. 用清晰的提交信息说明动机、行为变化和验证方式。
5. 涉及安全问题时遵循 `SECURITY.md`，不要先开公开 Issue。

较大的行为变更建议先开 Issue 讨论。新增第三方代码时，请同时添加来源、固定版本或提交号以及许可证说明。

## English

Thank you for contributing. Before opening a pull request:

1. Ensure the change is compatible with GNU AGPL-3.0 and preserve third-party notices.
2. Never commit environment files, `storage.keys.json`, databases, logs, APKs, signing material, or private service endpoints.
3. Run `pnpm test` and `pnpm build`; run the relevant Gradle checks for Android changes.
4. Explain the motivation, behavior change, and verification in clear commit and PR messages.
5. Follow `SECURITY.md` for vulnerabilities instead of opening a public issue.

Discuss substantial behavior changes in an issue first. New third-party code must include its source, pinned version or commit, and license information.
