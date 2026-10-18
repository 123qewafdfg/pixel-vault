'use strict';

const fs = require('fs');
const path = require('path');

const CONFIG_PATH = path.join(__dirname, 'storage.keys.json');
// 完整 APK 由官网提供；后端只保存增量补丁，并统一使用 uniCloud 内置存储。
const FULL_BACKENDS = [];
const PATCH_BACKENDS = ['builtin'];
const BACKENDS = [...new Set([...FULL_BACKENDS, ...PATCH_BACKENDS])];

function readConfig() {
  if (!fs.existsSync(CONFIG_PATH)) throw new Error('UPDATE_STORAGE_NOT_CONFIGURED');
  const config = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8').replace(/^\uFEFF/, ''));
  const prefix = typeof config.prefix === 'string' && /^[A-Za-z0-9/_-]{1,120}$/.test(config.prefix)
    ? config.prefix.replace(/^\/+|\/+$/g, '')
    : 'trustmark-updates';
  const urlExpiresSeconds = Number.isSafeInteger(config.urlExpiresSeconds)
    ? Math.min(86400, Math.max(300, config.urlExpiresSeconds))
    : 3600;
  return { ...config, prefix, urlExpiresSeconds };
}

function configured(config, backend) {
  const value = config[backend] || {};
  if (!value.enabled) return false;
  return backend === 'builtin';
}

function requireAllConfigured(config, backends) {
  const missing = backends.filter((backend) => !configured(config, backend));
  if (missing.length) throw new Error(`UPDATE_STORAGE_NOT_CONFIGURED:${missing.join(',')}`);
}

function safeObjectKey(config, relativeKey) {
  if (typeof relativeKey !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._/-]{0,240}$/.test(relativeKey) || relativeKey.includes('..')) {
    throw new Error('INVALID_UPDATE_OBJECT_KEY');
  }
  return `${config.prefix}/${relativeKey}`;
}

async function uploadOne(config, backend, objectKey, bytes, contentType) {
  if (backend === 'builtin') {
    const result = await uniCloud.uploadFile({ cloudPath: objectKey, fileContent: bytes });
    if (!result || !result.fileID) throw new Error('BUILTIN_UPLOAD_NO_FILE_ID');
    return { backend, fileId: result.fileID, objectKey };
  }
  throw new Error(`UNSUPPORTED_UPDATE_BACKEND:${backend}`);
}

async function removeOne(config, reference) {
  if (!reference) return;
  if (reference.backend === 'builtin') {
    if (reference.fileId) await uniCloud.deleteFile({ fileList: [reference.fileId] });
    return;
  }
  // 历史版本可能仍带有已停用的 COS/OSS 引用；不再访问这些账户。
}

async function replicate(relativeKey, bytes, contentType, backends = BACKENDS) {
  if (!Buffer.isBuffer(bytes) || bytes.length < 1) throw new Error('INVALID_UPDATE_BYTES');
  if (!Array.isArray(backends) || !backends.length || backends.some((backend) => !BACKENDS.includes(backend))) {
    throw new Error('INVALID_UPDATE_BACKENDS');
  }
  const config = readConfig();
  requireAllConfigured(config, backends);
  const objectKey = safeObjectKey(config, relativeKey);
  const uploaded = [];
  try {
    for (const backend of backends) uploaded.push(await uploadOne(config, backend, objectKey, bytes, contentType));
    return uploaded;
  } catch (error) {
    await Promise.allSettled(uploaded.map((reference) => removeOne(config, reference)));
    const wrapped = new Error(`UPDATE_REPLICATION_FAILED:${uploaded.length}:${error && error.message ? error.message : 'unknown'}`);
    wrapped.cause = error;
    throw wrapped;
  }
}

function replicateFull(relativeKey, bytes, contentType) {
  return replicate(relativeKey, bytes, contentType, FULL_BACKENDS);
}

function replicatePatch(relativeKey, bytes, contentType) {
  return replicate(relativeKey, bytes, contentType, PATCH_BACKENDS);
}

function uploadBuiltinPatch(relativeKey, bytes, contentType) {
  return replicate(relativeKey, bytes, contentType, ['builtin']);
}

async function signedUrl(config, reference, downloadName) {
  const disposition = downloadName ? `attachment; filename="${downloadName}"` : null;
  if (reference.backend === 'builtin') {
    const result = await uniCloud.getTempFileURL({ fileList: [reference.fileId] });
    const item = result && result.fileList && result.fileList[0];
    const url = item && (item.tempFileURL || item.download_url);
    if (!url) throw new Error('BUILTIN_SIGN_FAILED');
    return url;
  }
  throw new Error(`UNSUPPORTED_UPDATE_BACKEND:${reference.backend}`);
}

async function mirrorUrls(references, order = BACKENDS) {
  if (!Array.isArray(references)) throw new Error('INVALID_UPDATE_REPLICAS');
  const config = readConfig();
  const byBackend = new Map(references.map((item) => [item.backend, item]));
  const results = await Promise.allSettled(order.map(async (backend) => {
    const reference = byBackend.get(backend);
    if (!reference) throw new Error(`MISSING_UPDATE_REPLICA:${backend}`);
    return { backend, url: await signedUrl(config, reference) };
  }));
  const available = results
    .filter((item) => item.status === 'fulfilled')
    .map((item) => item.value);
  if (!available.length) throw new Error('NO_UPDATE_MIRROR_AVAILABLE');
  return available;
}

function fullUrls(references) {
  return mirrorUrls(references, FULL_BACKENDS);
}

function patchUrls(references) {
  return mirrorUrls(references, PATCH_BACKENDS);
}

async function downloadUrls(references, downloadName) {
  return [];
}

async function removeReplicas(references) {
  const config = readConfig();
  const results = await Promise.allSettled((references || []).map((reference) => removeOne(config, reference)));
  const failures = results.filter((item) => item.status === 'rejected');
  if (failures.length) throw new Error(`UPDATE_REPLICA_DELETE_FAILED:${failures.length}`);
}

function status() {
  const config = readConfig();
  return {
    full: FULL_BACKENDS.map((backend) => ({ backend, configured: configured(config, backend) })),
    patch: PATCH_BACKENDS.map((backend) => ({ backend, configured: configured(config, backend) }))
  };
}

module.exports = {
  replicate,
  replicateFull,
  replicatePatch,
  uploadBuiltinPatch,
  mirrorUrls,
  fullUrls,
  patchUrls,
  downloadUrls,
  removeReplicas,
  status,
  BACKENDS,
  FULL_BACKENDS,
  PATCH_BACKENDS
};
