'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const endpoint = process.env.UPDATE_ENDPOINT || '';
if (!endpoint) throw new Error('UPDATE_ENDPOINT is required');
const backendDir = path.resolve(__dirname, '..');
const assetsDir = path.join(backendDir, 'release-assets');

function fail(message) {
  throw new Error(message);
}

function hashFile(file) {
  const hash = crypto.createHash('sha256');
  const descriptor = fs.openSync(file, 'r');
  const buffer = Buffer.allocUnsafe(1024 * 1024);
  try {
    while (true) {
      const count = fs.readSync(descriptor, buffer, 0, buffer.length, null);
      if (!count) break;
      hash.update(buffer.subarray(0, count));
    }
  } finally {
    fs.closeSync(descriptor);
  }
  return hash.digest('hex');
}

function absoluteFile(baseDir, value, label) {
  if (typeof value !== 'string' || !value.trim()) fail(`${label} 路径无效`);
  const resolved = path.resolve(baseDir, value);
  if (!fs.statSync(resolved, { throwIfNoEntry: false })?.isFile()) fail(`${label} 不存在：${resolved}`);
  return resolved;
}

function validateManifest(raw, manifestDir) {
  if (!Number.isSafeInteger(raw.versionCode) || raw.versionCode < 2) fail('versionCode 无效');
  if (typeof raw.versionName !== 'string' || !raw.versionName.trim()) fail('versionName 无效');
  if (typeof raw.downloadUrl !== 'string' || !/^https:\/\//i.test(raw.downloadUrl)) fail('downloadUrl 必须是 HTTPS 地址');
  const fullPath = absoluteFile(manifestDir, raw.fullApk, '完整 APK');
  const resultApk = {
    size: fs.statSync(fullPath).size,
    sha256: hashFile(fullPath)
  };
  const patches = Array.isArray(raw.patches) ? raw.patches.map((item) => {
    if (!Number.isSafeInteger(item.fromVersionCode) || item.fromVersionCode < 1 || item.fromVersionCode >= raw.versionCode) fail('补丁来源版本无效');
    if (typeof item.baseApkSha256 !== 'string' || !/^[0-9a-f]{64}$/i.test(item.baseApkSha256)) fail(`v${item.fromVersionCode} 基础 APK 哈希无效`);
    const sourcePath = absoluteFile(manifestDir, item.file, `v${item.fromVersionCode} 补丁`);
    const assetName = `v${item.fromVersionCode}-to-v${raw.versionCode}.tmdelta.gz`;
    return {
      fromVersionCode: item.fromVersionCode,
      sourcePath,
      assetName,
      size: fs.statSync(sourcePath).size,
      sha256: hashFile(sourcePath),
      baseApkSha256: item.baseApkSha256.toLowerCase(),
      resultApkSha256: resultApk.sha256
    };
  }) : [];
  if (patches.length < 1 || patches.length > 8) fail('补丁数量必须为 1 到 8');
  if (new Set(patches.map((item) => item.fromVersionCode)).size !== patches.length) fail('补丁来源版本重复');
  return {
    versionCode: raw.versionCode,
    versionName: raw.versionName.trim(),
    notes: typeof raw.notes === 'string' ? raw.notes : '',
    force: raw.force === true,
    downloadUrl: raw.downloadUrl,
    resultApk,
    patches
  };
}

function prepare(release) {
  fs.mkdirSync(assetsDir, { recursive: true });
  for (const patch of release.patches) {
    const target = path.join(assetsDir, patch.assetName);
    fs.copyFileSync(patch.sourcePath, target);
    if (fs.statSync(target).size !== patch.size || hashFile(target) !== patch.sha256) fail(`补丁准备校验失败：${patch.assetName}`);
  }
  console.log(JSON.stringify({
    ok: true,
    mode: 'prepare',
    versionCode: release.versionCode,
    assetDirectory: assetsDir,
    assets: release.patches.map(({ fromVersionCode, assetName, size, sha256 }) => ({ fromVersionCode, assetName, size, sha256 }))
  }, null, 2));
}

async function publish(release) {
  const config = JSON.parse(fs.readFileSync(path.join(backendDir, 'storage.keys.json'), 'utf8').replace(/^\uFEFF/, ''));
  for (const patch of release.patches) {
    const deployedAsset = path.join(assetsDir, patch.assetName);
    if (!fs.statSync(deployedAsset, { throwIfNoEntry: false })?.isFile()) fail(`云函数资源未准备：${patch.assetName}`);
    if (fs.statSync(deployedAsset).size !== patch.size || hashFile(deployedAsset) !== patch.sha256) fail(`云函数资源校验失败：${patch.assetName}`);
  }
  const payload = {
    action: 'tm-update-publish-builtin-patch',
    adminSecret: config.adminSecret,
    versionCode: release.versionCode,
    versionName: release.versionName,
    notes: release.notes,
    force: release.force,
    downloadUrl: release.downloadUrl,
    resultApk: release.resultApk,
    patches: release.patches.map(({ fromVersionCode, assetName, size, sha256, baseApkSha256, resultApkSha256 }) => ({
      fromVersionCode,
      assetName,
      size,
      sha256,
      baseApkSha256,
      resultApkSha256
    }))
  };
  const response = await fetch(endpoint, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload)
  });
  const result = await response.json();
  if (!response.ok || !result.ok) fail(`发布失败：${response.status} ${result.code || result.message || 'UNKNOWN'}`);
  console.log(JSON.stringify({
    ok: true,
    mode: 'publish',
    versionCode: release.versionCode,
    apkSha256: release.resultApk.sha256,
    fullSize: release.resultApk.size,
    patches: payload.patches.map(({ fromVersionCode, size, sha256 }) => ({ fromVersionCode, size, sha256 })),
    storage: result.data
  }, null, 2));
}

async function main() {
  const mode = process.argv[2];
  const argument = process.argv[3];
  if (!['--prepare', '--publish'].includes(mode) || !argument) {
    console.log('用法：node tools/publish-update.js <--prepare|--publish> <发布清单.json>');
    return;
  }
  const manifestPath = path.resolve(process.cwd(), argument);
  const raw = JSON.parse(fs.readFileSync(manifestPath, 'utf8').replace(/^\uFEFF/, ''));
  const release = validateManifest(raw, path.dirname(manifestPath));
  if (mode === '--prepare') prepare(release);
  else await publish(release);
}

main().catch((error) => {
  console.error(error && error.message ? error.message : error);
  process.exitCode = 1;
});
