# 官方网站（镜像） / Official Site (mirror)

## 现状

- 发布地址：https://123qewafdfg.github.io/pixel-vault/
- 原始官方页：https://env-00jy6xru2hon-static.normal.cloudstatic.cn/
- 发布分支：`gh-pages`（根目录即站点根）
- 源目录：`site/`

## 与原始站的差异

只做了一处必要改动：把绝对路径改成相对路径，适配 GitHub Pages 的 `/pixel-vault/` 子路径。

```text
/app-icon.webp          -> ./app-icon.webp
/assets/index-*.js      -> ./assets/index-*.js
/assets/index-*.css     -> ./assets/index-*.css
```

`site/assets/index-*.js` 里 6 处 `/app-icon.webp` 同样改为 `./app-icon.webp`。除此之外页面内容、样式、脚本、图标均与官方站一致。

站点下载按钮调用的仍是官方下载接口（`/share`，返回安装包地址），属于公开的下载接口，不含密钥或后端配置。该接口返回 `Access-Control-Allow-Origin: *`，因此从 `github.io` 域名调用正常。

## 更新方式

```powershell
# 1. 用最新构建产物覆盖 site/（保持上面提到的相对路径改写）
# 2. 提交到 main
git add site docs/OFFICIAL_SITE.md
git commit -m "site: 更新官方下载页镜像"

# 3. 重新生成 gh-pages 分支
git branch -D gh-pages
git subtree split --prefix=site -b gh-pages

# 4. 推送
git push origin main gh-pages
```

`site/.nojekyll` 用于关闭 GitHub Pages 的 Jekyll 处理，避免构建产物被二次改写。
