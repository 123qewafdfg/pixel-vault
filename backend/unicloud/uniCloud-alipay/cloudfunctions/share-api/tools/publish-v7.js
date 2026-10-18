const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { Transform } = require('stream');
const { pipeline } = require('stream/promises');
const COS = require('cos-nodejs-sdk-v5');

const endpoint = process.env.UPDATE_ENDPOINT || '';
if (!endpoint) throw new Error('UPDATE_ENDPOINT is required');
const backendDir = path.resolve(__dirname, '..');
const config = JSON.parse(fs.readFileSync(path.join(backendDir, 'storage.keys.json'), 'utf8'));
const appDir = 'C:\\Users\\ouya\\Documents\\Codex\\2026-10-09\\sh\\outputs\\steganography-ui';
const fullPath = path.join(appDir, 'android', 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk');
const patchPath = path.join(appDir, 'android', 'build', 'updates', 'v6-to-v7.tmdelta.gz');
const cos = new COS({ SecretId: config.cos.secretId, SecretKey: config.cos.secretKey });
const prefix = `${config.prefix || 'trustmark-updates'}/staging/v7-${Date.now()}-${crypto.randomBytes(4).toString('hex')}`;
const stagedKeys = [];
const encodedFullPath = `${fullPath}.xor-stage`;

function hashFile(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

function putObject(key, file) {
  return new Promise((resolve, reject) => {
    cos.putObject({ Bucket: config.cos.bucket, Region: config.cos.region, Key: key, Body: fs.createReadStream(file) }, (error) => {
      if (error) reject(error); else resolve();
    });
  });
}

function signedUrl(key) {
  return new Promise((resolve, reject) => {
    cos.getObjectUrl({ Bucket: config.cos.bucket, Region: config.cos.region, Key: key, Sign: true, Expires: 3600 }, (error, data) => {
      if (error) reject(error); else resolve(data.Url);
    });
  });
}

function deleteObject(key) {
  return new Promise((resolve) => {
    cos.deleteObject({ Bucket: config.cos.bucket, Region: config.cos.region, Key: key }, () => resolve());
  });
}

async function encodeFullApk() {
  const xor = new Transform({
    transform(chunk, _encoding, callback) {
      for (let index = 0; index < chunk.length; index += 1) chunk[index] ^= 0xa5;
      callback(null, chunk);
    }
  });
  await pipeline(fs.createReadStream(fullPath), xor, fs.createWriteStream(encodedFullPath));
}

async function main() {
  await encodeFullApk();
  const full = { path: encodedFullPath, key: `${prefix}/app-release.dat` };
  const patch = { path: patchPath, key: `${prefix}/v6-to-v7.tmdelta.gz` };
  for (const artifact of [full, patch]) {
    if (!fs.existsSync(artifact.path)) throw new Error(`MISSING_ARTIFACT:${artifact.path}`);
    await putObject(artifact.key, artifact.path);
    stagedKeys.push(artifact.key);
  }
  const fullStat = fs.statSync(full.path);
  const patchStat = fs.statSync(patch.path);
  const payload = {
    action: 'tm-update-publish',
    adminSecret: config.adminSecret,
    versionCode: 7,
    versionName: '2.5-native',
    notes: '修复部分设备点击提取时闪退的问题；第2步默认使用新版承载图，第4步恢复风景图。',
    force: false,
    fullApk: {
      sourceUrl: await signedUrl(full.key),
      size: fullStat.size,
      sha256: hashFile(fullPath),
      transferEncoding: 'xor-v1'
    },
    patches: [{
      fromVersionCode: 6,
      sourceUrl: await signedUrl(patch.key),
      size: patchStat.size,
      sha256: hashFile(patch.path),
      baseApkSha256: 'b5871b790e0e00453a532d493402a82760eb4adaae6335ee922796e82586facc',
      resultApkSha256: '571c11b22d4bb2dd63d1a997f5d89153c7fe4b4e990c78b1a1a747c8bff4e810'
    }]
  };
  const response = await fetch(endpoint, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload)
  });
  const result = await response.json();
  if (!response.ok || !result.ok) throw new Error(`PUBLISH_FAILED:${response.status}:${result.code || result.message || 'UNKNOWN'}`);
  console.log(JSON.stringify({ ok: true, versionCode: 7, fullSize: fullStat.size, patchSize: patchStat.size, result: result.data }));
}

main()
  .catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  })
  .finally(async () => {
    await Promise.all(stagedKeys.map(deleteObject));
    if (fs.existsSync(encodedFullPath)) fs.unlinkSync(encodedFullPath);
  });
