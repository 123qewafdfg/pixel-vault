'use strict';

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const storageRouter = require('./storage-router');

const RESOURCE_COLLECTION = 'trustmark_resources';
const RESOURCE_PART_BATCH_COLLECTION = 'trustmark_resource_part_batches';
const RATE_LIMIT_COLLECTION = 'trustmark_rate_limits';
const VERSION_COLLECTION = 'trustmark_app_versions';
const FALLBACK_COLLECTION = 'trustmark_download_fallbacks';
const MAX_BODY_BYTES = 256 * 1024;
const MAX_ENCODED_BODY_CHARS = 384 * 1024;
const MAX_PARTS = 65535;
const MAX_BILI_PARTS = 64;
const PARTS_PER_BATCH = 50;
const DRAFT_TTL_MS = 60 * 60 * 1000;
const MAX_URL_CHARS = 2048;
const MAX_METADATA_BYTES = 4096;
const MAX_INLINE_CIPHER_BYTES = 1028;
const MAX_UPDATE_BYTES = 150 * 1024 * 1024;
const MAX_CIPHER_BYTES_PER_PART = 21 * 1024 * 1024;
const CLEANUP_BATCH_SIZE = 100;
const CLEANUP_MAX_BATCHES = 5;
const SHORT_ID_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const VALID_ACTIONS = new Set([
  'tm-create',
  'tm-create-draft',
  'tm-create-parts',
  'tm-create-finalize',
  'tm-get',
  'tm-get-parts',
  'tm-version',
  'tm-download-android',
  'tm-download-fallbacks',
  'tm-download-fallbacks-admin',
  'tm-update-publish',
  'tm-update-publish-builtin-patch',
  'tm-update-repair',
  'tm-update-delete',
  'tm-update-normalize-storage'
]);

const OFFICIAL_SITE_URL = 'https://github.com/123qewafdfg/pixel-vault/releases/latest';

const APP_VERSION = {
  versionCode: 4,
  versionName: '2.2-native',
  notes: '新增自动缓存清理、GIF/音频/文本预览和 Android 10 兼容优化。',
  downloadUrl: OFFICIAL_SITE_URL,
  force: false
};

const RATE_LIMITS = {
  'tm-create': { group: 'create', windowMs: 60 * 60 * 1000, limit: 10 },
  'tm-create-draft': { group: 'create', windowMs: 60 * 60 * 1000, limit: 10 },
  'tm-create-parts': { group: 'create-parts', windowMs: 60 * 60 * 1000, limit: 2000 },
  'tm-create-finalize': { group: 'create', windowMs: 60 * 60 * 1000, limit: 10 },
  'tm-get': { group: 'read', windowMs: 60 * 1000, limit: 30 },
  'tm-get-parts': { group: 'read-parts', windowMs: 60 * 1000, limit: 1000 },
  'tm-version': { group: 'version', windowMs: 60 * 1000, limit: 30 },
  'tm-download-android': { group: 'download', windowMs: 60 * 1000, limit: 30 },
  'tm-download-fallbacks': { group: 'download-fallbacks', windowMs: 60 * 1000, limit: 60 },
  'tm-download-fallbacks-admin': { group: 'download-fallbacks-admin', windowMs: 60 * 60 * 1000, limit: 30 },
  'tm-update-publish': { group: 'update-admin', windowMs: 60 * 60 * 1000, limit: 5 },
  'tm-update-publish-builtin-patch': { group: 'update-admin', windowMs: 60 * 60 * 1000, limit: 5 },
  'tm-update-repair': { group: 'update-repair-admin', windowMs: 60 * 60 * 1000, limit: 5 },
  'tm-update-delete': { group: 'update-delete-admin', windowMs: 60 * 60 * 1000, limit: 5 },
  'tm-update-normalize-storage': { group: 'update-normalize-admin', windowMs: 60 * 60 * 1000, limit: 5 }
};

function jsonResponse(statusCode, payload) {
  return {
    mpserverlessComposedResponse: true,
    isBase64Encoded: false,
    statusCode,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
      'access-control-allow-origin': '*',
      'access-control-allow-methods': 'POST, OPTIONS',
      'access-control-allow-headers': 'content-type'
    },
    body: JSON.stringify(payload)
  };
}

function parseRequest(event) {
  if (!event || typeof event !== 'object') return {};
  if (!Object.prototype.hasOwnProperty.call(event, 'body')) return event;
  let body = event.body || '';
  if (event.isBase64Encoded && typeof body === 'string') {
    if (body.length > MAX_ENCODED_BODY_CHARS) throw new Error('REQUEST_TOO_LARGE');
    body = Buffer.from(body, 'base64').toString('utf8');
  }
  if (!body) return {};
  if (typeof body === 'object') return body;
  if (Buffer.byteLength(body, 'utf8') > MAX_BODY_BYTES) throw new Error('REQUEST_TOO_LARGE');
  try {
    return JSON.parse(body);
  } catch (_) {
    throw new Error('INVALID_JSON');
  }
}

function sha256(value) {
  return crypto.createHash('sha256').update(value, 'utf8').digest('hex');
}

function sha256Bytes(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

function requireAdmin(params) {
  const expected = process.env.UPDATE_ADMIN_SECRET || require('./storage.keys.json').adminSecret || '';
  const provided = params && typeof params.adminSecret === 'string' ? params.adminSecret : '';
  if (expected.length < 32 || provided.length !== expected.length) throw new Error('UPDATE_ADMIN_DENIED');
  if (!crypto.timingSafeEqual(Buffer.from(provided, 'utf8'), Buffer.from(expected, 'utf8'))) throw new Error('UPDATE_ADMIN_DENIED');
}

function requireHttpsUrl(value) {
  if (typeof value !== 'string' || value.length < 8 || value.length > MAX_URL_CHARS) throw new Error('INVALID_UPDATE_SOURCE');
  let parsed;
  try { parsed = new URL(value); } catch (_) { throw new Error('INVALID_UPDATE_SOURCE'); }
  if (parsed.protocol !== 'https:' || parsed.username || parsed.password || parsed.hash) throw new Error('INVALID_UPDATE_SOURCE');
  return parsed.toString();
}

function validateUpdateArtifact(value, code) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('INVALID_UPDATE_MANIFEST');
  if (!Number.isSafeInteger(value.size) || value.size < 1 || value.size > MAX_UPDATE_BYTES) throw new Error('INVALID_UPDATE_MANIFEST');
  const transferEncoding = value.transferEncoding == null ? null : value.transferEncoding;
  if (transferEncoding !== null && transferEncoding !== 'xor-v1') throw new Error('INVALID_UPDATE_MANIFEST');
  return {
    sourceUrl: requireHttpsUrl(value.sourceUrl),
    size: value.size,
    sha256: requireHash(value.sha256, 'INVALID_UPDATE_MANIFEST'),
    transferEncoding,
    code
  };
}

function validateUpdateResult(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('INVALID_UPDATE_MANIFEST');
  if (!Number.isSafeInteger(value.size) || value.size < 1 || value.size > MAX_UPDATE_BYTES) throw new Error('INVALID_UPDATE_MANIFEST');
  return { size: value.size, sha256: requireHash(value.sha256, 'INVALID_UPDATE_MANIFEST') };
}

async function downloadUpdateArtifact(artifact) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 240000);
  try {
    const response = await fetch(artifact.sourceUrl, {
      redirect: 'follow',
      signal: controller.signal,
      headers: { 'user-agent': 'TrustMarkUpdatePublisher/1.0' }
    });
    if (!response.ok) throw new Error(`UPDATE_SOURCE_HTTP_${response.status}`);
    if (!response.url.startsWith('https://')) throw new Error('INVALID_UPDATE_SOURCE');
    const declared = Number(response.headers.get('content-length') || 0);
    if (declared > MAX_UPDATE_BYTES || (declared > 0 && declared !== artifact.size)) throw new Error('UPDATE_SOURCE_SIZE_MISMATCH');
    const bytes = Buffer.from(await response.arrayBuffer());
    if (bytes.length !== artifact.size) throw new Error('UPDATE_SOURCE_SIZE_MISMATCH');
    if (artifact.transferEncoding === 'xor-v1') {
      for (let index = 0; index < bytes.length; index += 1) bytes[index] ^= 0xa5;
    }
    if (sha256Bytes(bytes) !== artifact.sha256) throw new Error('UPDATE_SOURCE_HASH_MISMATCH');
    return bytes;
  } finally {
    clearTimeout(timeout);
  }
}

function updateChannel(params = {}) {
  const channel = params.channel == null || params.channel === '' ? 'main' : params.channel;
  if (channel !== 'main' && channel !== 'extract') throw new Error('INVALID_UPDATE_CHANNEL');
  return channel;
}

function recordMatchesChannel(record, channel) {
  return (record.channel || 'main') === channel;
}

async function findUpdateRecord(collection, versionCode, channel) {
  const found = await collection.where({ version_code: versionCode }).limit(100).get();
  return (found.data || []).find((record) => recordMatchesChannel(record, channel));
}

function updateStoragePrefix(channel, versionCode) {
  return `${channel === 'main' ? '' : `${channel}/`}v${versionCode}`;
}

async function publishUpdate(db, params) {
  requireAdmin(params);
  const channel = updateChannel(params);
  if (!Number.isSafeInteger(params.versionCode) || params.versionCode < 1) throw new Error('INVALID_UPDATE_MANIFEST');
  if (typeof params.versionName !== 'string' || params.versionName.length < 1 || params.versionName.length > 64) throw new Error('INVALID_UPDATE_MANIFEST');
  if (typeof params.notes !== 'string' || params.notes.length > 4000) throw new Error('INVALID_UPDATE_MANIFEST');
  const full = validateUpdateArtifact(params.fullApk, 'full');
  const patches = Array.isArray(params.patches) ? params.patches.map((item) => {
    if (!Number.isSafeInteger(item.fromVersionCode) || item.fromVersionCode < 1 || item.fromVersionCode >= params.versionCode) throw new Error('INVALID_UPDATE_MANIFEST');
    return {
      ...validateUpdateArtifact(item, `patch-${item.fromVersionCode}`),
      fromVersionCode: item.fromVersionCode,
      baseApkSha256: requireHash(item.baseApkSha256, 'INVALID_UPDATE_MANIFEST'),
      resultApkSha256: requireHash(item.resultApkSha256, 'INVALID_UPDATE_MANIFEST')
    };
  }) : [];
  if (new Set(patches.map((item) => item.fromVersionCode)).size !== patches.length) throw new Error('INVALID_UPDATE_MANIFEST');

  const collection = db.collection(VERSION_COLLECTION);
  if (await findUpdateRecord(collection, params.versionCode, channel)) throw new Error('UPDATE_VERSION_EXISTS');
  const storagePrefix = updateStoragePrefix(channel, params.versionCode);

  const uploaded = [];
  try {
    const fullBytes = await downloadUpdateArtifact(full);
    const fullRefs = await storageRouter.replicateFull(`${storagePrefix}/app-release.bin`, fullBytes, 'application/octet-stream');
    uploaded.push(fullRefs);
    const patchRecords = [];
    for (const patch of patches) {
      const patchBytes = await downloadUpdateArtifact(patch);
      const references = await storageRouter.replicatePatch(`${storagePrefix}/from-v${patch.fromVersionCode}.tmdelta.gz`, patchBytes, 'application/gzip');
      uploaded.push(references);
      patchRecords.push({
        from_version_code: patch.fromVersionCode,
        size: patch.size,
        sha256: patch.sha256,
        base_apk_sha256: patch.baseApkSha256,
        result_apk_sha256: patch.resultApkSha256,
        references
      });
    }
    await collection.add({
      channel,
      version_code: params.versionCode,
      version_name: params.versionName,
      notes: params.notes,
      force: params.force === true,
      active: true,
      created_at: Date.now(),
      download_url: OFFICIAL_SITE_URL,
      full_apk: { size: full.size, sha256: full.sha256, references: fullRefs },
      patches: patchRecords
    });
    return { channel, versionCode: params.versionCode, storage: storageRouter.status(), patchCount: patchRecords.length };
  } catch (error) {
    await Promise.allSettled(uploaded.map((references) => storageRouter.removeReplicas(references)));
    throw error;
  }
}

async function publishBuiltinPatchUpdate(db, params) {
  requireAdmin(params);
  const channel = updateChannel(params);
  if (!Number.isSafeInteger(params.versionCode) || params.versionCode < 2) throw new Error('INVALID_UPDATE_MANIFEST');
  if (typeof params.versionName !== 'string' || params.versionName.length < 1 || params.versionName.length > 64) throw new Error('INVALID_UPDATE_MANIFEST');
  if (typeof params.notes !== 'string' || params.notes.length > 4000) throw new Error('INVALID_UPDATE_MANIFEST');
  const downloadUrl = requireHttpsUrl(params.downloadUrl);
  const resultApk = validateUpdateResult(params.resultApk);
  const inputs = Array.isArray(params.patches) ? params.patches : (params.patch ? [params.patch] : []);
  if (inputs.length < 1 || inputs.length > 8) throw new Error('INVALID_UPDATE_MANIFEST');
  const patches = inputs.map((patch) => {
    if (!patch || !Number.isSafeInteger(patch.fromVersionCode) || patch.fromVersionCode < 1 || patch.fromVersionCode >= params.versionCode) {
      throw new Error('INVALID_UPDATE_MANIFEST');
    }
    if (typeof patch.assetName !== 'string' || !/^v[1-9][0-9]*-to-v[1-9][0-9]*\.tmdelta\.gz$/.test(patch.assetName)) {
      throw new Error('INVALID_UPDATE_MANIFEST');
    }
    const size = Number(patch.size);
    if (!Number.isSafeInteger(size) || size < 1 || size > MAX_UPDATE_BYTES) throw new Error('INVALID_UPDATE_MANIFEST');
    const sha256 = requireHash(patch.sha256, 'INVALID_UPDATE_MANIFEST');
    const baseApkSha256 = requireHash(patch.baseApkSha256, 'INVALID_UPDATE_MANIFEST');
    const resultApkSha256 = requireHash(patch.resultApkSha256, 'INVALID_UPDATE_MANIFEST');
    if (resultApkSha256 !== resultApk.sha256) throw new Error('INVALID_UPDATE_MANIFEST');
    const assetPath = path.join(__dirname, 'release-assets', patch.assetName);
    const bytes = fs.readFileSync(assetPath);
    if (bytes.length !== size) throw new Error('UPDATE_SOURCE_SIZE_MISMATCH');
    if (sha256Bytes(bytes) !== sha256) throw new Error('UPDATE_SOURCE_HASH_MISMATCH');
    return { ...patch, size, sha256, baseApkSha256, resultApkSha256, bytes };
  });
  if (new Set(patches.map((patch) => patch.fromVersionCode)).size !== patches.length) throw new Error('INVALID_UPDATE_MANIFEST');

  const collection = db.collection(VERSION_COLLECTION);
  if (await findUpdateRecord(collection, params.versionCode, channel)) throw new Error('UPDATE_VERSION_EXISTS');
  const storagePrefix = updateStoragePrefix(channel, params.versionCode);
  const uploaded = [];
  try {
    const patchRecords = [];
    for (const patch of patches) {
      const references = await storageRouter.uploadBuiltinPatch(
        `${storagePrefix}/from-v${patch.fromVersionCode}.tmdelta.gz`,
        patch.bytes,
        'application/gzip'
      );
      uploaded.push(references);
      patchRecords.push({
        from_version_code: patch.fromVersionCode,
        size: patch.size,
        sha256: patch.sha256,
        base_apk_sha256: patch.baseApkSha256,
        result_apk_sha256: patch.resultApkSha256,
        references
      });
    }
    await collection.add({
      channel,
      version_code: params.versionCode,
      version_name: params.versionName,
      notes: params.notes,
      force: params.force === true,
      active: true,
      created_at: Date.now(),
      download_url: downloadUrl,
      result_apk: resultApk,
      full_apk: null,
      patches: patchRecords
    });
    return { channel, versionCode: params.versionCode, patchBackend: 'builtin', patchCount: patchRecords.length };
  } catch (error) {
    await Promise.allSettled(uploaded.map((references) => storageRouter.removeReplicas(references)));
    throw error;
  }
}

async function repairUpdate(db, params) {
  requireAdmin(params);
  const channel = updateChannel(params);
  if (!Number.isSafeInteger(params.versionCode) || params.versionCode < 1) throw new Error('INVALID_UPDATE_MANIFEST');
  const full = validateUpdateArtifact(params.fullApk, 'full');
  const collection = db.collection(VERSION_COLLECTION);
  const record = await findUpdateRecord(collection, params.versionCode, channel);
  if (!record || !record.full_apk) throw new Error('UPDATE_VERSION_NOT_FOUND');
  if (Number(record.full_apk.size) !== full.size || record.full_apk.sha256 !== full.sha256) {
    throw new Error('UPDATE_REPAIR_ARTIFACT_MISMATCH');
  }

  const bytes = await downloadUpdateArtifact(full);
  const references = await storageRouter.replicateFull(
    `${updateStoragePrefix(channel, params.versionCode)}/app-release.bin`,
    bytes,
    'application/octet-stream'
  );
  try {
    await collection.doc(record._id).update({
      full_apk: { size: full.size, sha256: full.sha256, references },
      repaired_at: Date.now()
    });
  } catch (error) {
    await Promise.allSettled([storageRouter.removeReplicas(references)]);
    throw error;
  }
  return { channel, versionCode: params.versionCode, storage: storageRouter.status(), repaired: true };
}

async function normalizeUpdateStorage(db, params) {
  requireAdmin(params);
  const channel = updateChannel(params);
  if (!Number.isSafeInteger(params.versionCode) || params.versionCode < 1) throw new Error('INVALID_UPDATE_MANIFEST');
  const collection = db.collection(VERSION_COLLECTION);
  const record = await findUpdateRecord(collection, params.versionCode, channel);
  if (!record || !record.full_apk || !Array.isArray(record.full_apk.references)) throw new Error('UPDATE_VERSION_NOT_FOUND');
  const cosReferences = record.full_apk.references.filter((item) => item && item.backend === 'cos');
  if (cosReferences.length !== 1) throw new Error('UPDATE_COS_FULL_REPLICA_REQUIRED');
  const obsolete = record.full_apk.references.filter((item) => item && item.backend !== 'cos');
  if (obsolete.length) await storageRouter.removeReplicas(obsolete);
  await collection.doc(record._id).update({
    full_apk: { ...record.full_apk, references: cosReferences },
    storage_normalized_at: Date.now()
  });
  return { channel, versionCode: params.versionCode, removedFullReplicas: obsolete.map((item) => item.backend), storage: storageRouter.status() };
}

async function deleteUpdate(db, params) {
  requireAdmin(params);
  const channel = updateChannel(params);
  if (!Number.isSafeInteger(params.versionCode) || params.versionCode < 1) throw new Error('INVALID_UPDATE_MANIFEST');
  const expectedSha256 = requireHash(params.expectedSha256, 'INVALID_UPDATE_MANIFEST');
  const collection = db.collection(VERSION_COLLECTION);
  const record = await findUpdateRecord(collection, params.versionCode, channel);
  if (!record) throw new Error('UPDATE_VERSION_NOT_FOUND');
  const recordSha256 = record.result_apk && record.result_apk.sha256
    ? record.result_apk.sha256
    : record.full_apk && record.full_apk.sha256;
  if (recordSha256 !== expectedSha256) throw new Error('UPDATE_DELETE_ARTIFACT_MISMATCH');

  const replicaGroups = [];
  if (record.full_apk && Array.isArray(record.full_apk.references)) replicaGroups.push(record.full_apk.references);
  for (const patch of record.patches || []) replicaGroups.push(patch.references);
  for (const references of replicaGroups) await storageRouter.removeReplicas(references);
  await collection.doc(record._id).remove();
  return { channel, versionCode: params.versionCode, deleted: true };
}

async function currentVersion(db, params = {}) {
  const channel = updateChannel(params);
  const found = await db.collection(VERSION_COLLECTION).where({ active: true }).limit(100).get();
  const record = (found.data || [])
    .filter((item) => (item.channel || 'main') === channel)
    .sort((a, b) => Number(b.version_code || 0) - Number(a.version_code || 0))[0];
  if (!record) {
    if (channel === 'extract') return { channel, versionCode: 1, versionName: '1.0', notes: '', force: false, downloadUrl: APP_VERSION.downloadUrl, patches: [] };
    return APP_VERSION;
  }
  const patches = [];
  for (const patch of record.patches || []) {
    const mirrors = await storageRouter.patchUrls(patch.references);
    patches.push({
      fromVersionCode: patch.from_version_code,
      size: patch.size,
      sha256: patch.sha256,
      baseApkSha256: patch.base_apk_sha256,
      resultApkSha256: patch.result_apk_sha256,
      url: mirrors[0].url,
      mirrors
    });
  }
  return {
    channel,
    versionCode: record.version_code,
    versionName: record.version_name,
    notes: record.notes || '',
    force: record.force === true,
    downloadUrl: OFFICIAL_SITE_URL,
    patches
  };
}

async function currentAndroidDownload(db) {
  return { external: true, downloadUrl: OFFICIAL_SITE_URL };
}

function fallbackRecord(record) {
  return {
    id: record._id,
    name: record.name,
    url: record.url,
    note: record.note || '',
    enabled: record.enabled === true,
    sort: Number.isSafeInteger(record.sort) ? record.sort : 0
  };
}

function validateFallback(params) {
  if (typeof params.name !== 'string' || !params.name.trim() || params.name.trim().length > 40) {
    throw new Error('INVALID_FALLBACK');
  }
  if (typeof params.note !== 'string' || params.note.length > 120) throw new Error('INVALID_FALLBACK');
  if (!Number.isSafeInteger(params.sort) || params.sort < 0 || params.sort > 10000) throw new Error('INVALID_FALLBACK');
  return {
    name: params.name.trim(),
    url: requireHttpsUrl(params.url),
    note: params.note.trim(),
    enabled: params.enabled !== false,
    sort: params.sort,
    updated_at: Date.now()
  };
}

async function downloadFallbacks(db, includeDisabled = false) {
  const result = await db.collection(FALLBACK_COLLECTION).limit(100).get();
  return (result.data || [])
    .filter((item) => includeDisabled || item.enabled === true)
    .sort((a, b) => Number(a.sort || 0) - Number(b.sort || 0) || Number(a.created_at || 0) - Number(b.created_at || 0))
    .map(fallbackRecord);
}

async function manageDownloadFallbacks(db, params) {
  requireAdmin(params);
  const operation = params.operation;
  if (operation === 'list') return downloadFallbacks(db, true);
  const collection = db.collection(FALLBACK_COLLECTION);
  if (operation === 'delete') {
    if (typeof params.id !== 'string' || !/^[A-Za-z0-9_-]{1,96}$/.test(params.id)) throw new Error('INVALID_FALLBACK');
    await collection.doc(params.id).remove();
    return { deleted: true, id: params.id };
  }
  if (operation !== 'upsert') throw new Error('INVALID_FALLBACK_OPERATION');
  const record = validateFallback(params);
  if (typeof params.id === 'string' && params.id) {
    if (!/^[A-Za-z0-9_-]{1,96}$/.test(params.id)) throw new Error('INVALID_FALLBACK');
    await collection.doc(params.id).update(record);
    return { ...record, id: params.id };
  }
  const created = await collection.add({ ...record, created_at: Date.now() });
  return { ...record, id: created.id || created._id };
}

function rateLimitKey(clientIp, group, windowStart) {
  const secret = process.env.RATE_LIMIT_SECRET;
  if (!secret || secret.length < 32) throw new Error('RATE_LIMIT_NOT_CONFIGURED');
  return crypto.createHmac('sha256', secret)
    .update(`${clientIp || 'unknown'}|${group}|${windowStart}`, 'utf8')
    .digest('hex');
}

async function enforceRateLimit(db, clientIp, action) {
  const config = RATE_LIMITS[action];
  const now = Date.now();
  const windowStart = Math.floor(now / config.windowMs) * config.windowMs;
  const expiresAt = windowStart + config.windowMs + (60 * 60 * 1000);
  const key = rateLimitKey(clientIp, config.group, windowStart);
  const collection = db.collection(RATE_LIMIT_COLLECTION);
  const found = await collection.where({ key }).limit(1).get();
  const record = found.data && found.data[0];
  if (!record) {
    try {
      await collection.add({ key, group: config.group, count: 1, window_start: windowStart, expires_at: expiresAt });
      return;
    } catch (_) {
      const retry = await collection.where({ key }).limit(1).get();
      const concurrent = retry.data && retry.data[0];
      if (!concurrent) throw new Error('RATE_LIMIT_STORE_FAILED');
      if (Number(concurrent.count || 0) >= config.limit) throw new Error('RATE_LIMITED');
      await collection.doc(concurrent._id).update({ count: db.command.inc(1), expires_at: expiresAt });
      return;
    }
  }
  if (Number(record.count || 0) >= config.limit) throw new Error('RATE_LIMITED');
  await collection.doc(record._id).update({ count: db.command.inc(1), expires_at: expiresAt });
}

function decodeBase64Url(value, expectedBytes, maxBytes, code) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]+$/.test(value)) throw new Error(code);
  let bytes;
  try {
    bytes = Buffer.from(value, 'base64url');
  } catch (_) {
    throw new Error(code);
  }
  if (bytes.toString('base64url') !== value) throw new Error(code);
  if (expectedBytes !== null && bytes.length !== expectedBytes) throw new Error(code);
  if (maxBytes !== null && (bytes.length === 0 || bytes.length > maxBytes)) throw new Error(code);
  return bytes;
}

function requireHash(value, code) {
  if (typeof value !== 'string' || !/^[0-9a-f]{64}$/.test(value)) throw new Error(code);
  return value;
}

function validateCloudSource(source, expectedContentSha256) {
  if (!source || typeof source !== 'object' || Array.isArray(source)) throw new Error('INVALID_PARTS');
  const hosts = {
    QUARK: ['pan.quark.cn'], UC: ['drive.uc.cn'], XUNLEI: ['pan.xunlei.com'],
    BAIDU: ['pan.baidu.com'], C139: ['yun.139.com'], PAN123: ['123pan.com', '123pan.cn', '123684.com', '123865.com', '123912.com', '123592.com'],
    PAN115: ['115.com', '115cdn.com', '115rc.com'], GUANGYA: ['guangyapan.com'],
    ILANZOU: ['ilanzou.com', 'ilanzou.net', 'ilanzou.org', 'ilanzou.cn'], LANZOU: []
  };
  const allowed = hosts[source.platform];
  if (!allowed || typeof source.shareUrl !== 'string' || source.shareUrl.length > MAX_URL_CHARS) throw new Error('INVALID_URL');
  let url;
  try { url = new URL(source.shareUrl); } catch (_) { throw new Error('INVALID_URL'); }
  const trustedHost = source.platform === 'LANZOU'
    ? /^(?:[a-z0-9-]+\.)*(?:lanzou[a-z0-9-]*|lan[zs]o[ux])\.(?:com|net|org|cn)$/.test(url.hostname)
    : allowed.some(host => url.hostname === host || url.hostname.endsWith('.' + host));
  if (url.protocol !== 'https:' || url.username || url.password || (url.port && url.port !== '443') || !trustedHost) throw new Error('INVALID_URL');
  if (typeof source.fileId !== 'string' || source.fileId.length < 1 || source.fileId.length > 1024 || /[\x00-\x1f]/.test(source.fileId)) throw new Error('INVALID_PARTS');
  if (source.password != null && (typeof source.password !== 'string' || source.password.length > 128 || /[\x00-\x1f]/.test(source.password))) throw new Error('INVALID_PARTS');
  const contentSha256 = source.contentSha256 == null
    ? expectedContentSha256
    : requireHash(source.contentSha256, 'INVALID_PARTS');
  if (contentSha256 !== expectedContentSha256) throw new Error('INVALID_PARTS');
  return { platform: source.platform, shareUrl: url.toString(), fileId: source.fileId, contentSha256, ...(source.password ? { password: source.password } : {}) };
}

function validatePart(part, expectedIndex) {
    if (!part || typeof part !== 'object' || Array.isArray(part)) throw new Error('INVALID_PARTS');
    if (!Number.isSafeInteger(part.partIndex) || part.partIndex !== expectedIndex) throw new Error('INVALID_PARTS');
    if (!Number.isSafeInteger(part.cipherSize) || part.cipherSize < 1 || part.cipherSize > MAX_CIPHER_BYTES_PER_PART) {
      throw new Error('INVALID_PARTS');
    }
    const remoteSha256 = requireHash(part.remoteSha256, 'INVALID_PARTS');
    let location;
    if (part.cloudSource != null) {
      if (part.downloadUrl != null) throw new Error('INVALID_PARTS');
      location = { cloudSource: validateCloudSource(part.cloudSource, remoteSha256) };
    } else {
      if (typeof part.downloadUrl !== 'string' || part.downloadUrl.length < 8 || part.downloadUrl.length > MAX_URL_CHARS) throw new Error('INVALID_URL');
      let url;
      try { url = new URL(part.downloadUrl); } catch (_) { throw new Error('INVALID_URL'); }
      if (url.protocol !== 'https:' || url.username || url.password || url.hash) throw new Error('INVALID_URL');
      location = { downloadUrl: url.toString() };
    }
    return {
      partIndex: expectedIndex,
      ...location,
      remoteSha256,
      cipherSha256: requireHash(part.cipherSha256, 'INVALID_PARTS'),
      cipherSize: part.cipherSize
    };
}

function isBilibiliPart(part) {
  if (!part.downloadUrl) return false;
  const host = new URL(part.downloadUrl).hostname.toLowerCase();
  return ['biliimg.com', 'hdslb.com'].some(domain => host === domain || host.endsWith(`.${domain}`));
}

function validateParts(value, startIndex = 0, maximumCount = MAX_PARTS) {
  if (!Array.isArray(value) || value.length < 1 || value.length > maximumCount ||
      !Number.isSafeInteger(startIndex) || startIndex < 0 || startIndex + value.length > MAX_PARTS) throw new Error('INVALID_PARTS');
  const parts = value.map((part, offset) => validatePart(part, startIndex + offset));
  if (parts.length > MAX_BILI_PARTS && parts.every(isBilibiliPart)) throw new Error('INVALID_PARTS');
  return parts;
}

function validateResourceHeader(params, allowInline = true) {
  if (!params || typeof params !== 'object' || Array.isArray(params)) throw new Error('INVALID_REQUEST');
  const protocolVersion = params.protocolVersion;
  if (protocolVersion !== 1 && protocolVersion !== 3 && !(allowInline && protocolVersion === 2)) throw new Error('INVALID_PROTOCOL');
  decodeBase64Url(params.fileKey, 32, null, 'INVALID_KEY');
  requireHash(params.originalSha256, 'INVALID_HASH');
  const metadata = decodeBase64Url(
    params.encryptedMetadata,
    null,
    protocolVersion === 2 ? MAX_INLINE_CIPHER_BYTES : MAX_METADATA_BYTES,
    'INVALID_METADATA'
  );
  if (protocolVersion === 2) {
    // 12-byte IV + at least 1 byte plaintext + 16-byte GCM tag.
    if (metadata.length < 29) throw new Error('INVALID_METADATA');
  }
  return {
    protocol_version: protocolVersion,
    file_key: params.fileKey,
    encrypted_metadata: params.encryptedMetadata,
    original_sha256: params.originalSha256,
  };
}

function validateCreate(params) {
  const header = validateResourceHeader(params, true);
  const parts = header.protocol_version === 2 ? [] : validateParts(params.parts);
  if (header.protocol_version === 1 && parts.some(part => part.cloudSource)) throw new Error('INVALID_PROTOCOL');
  if (header.protocol_version === 2 && (!Array.isArray(params.parts) || params.parts.length !== 0)) throw new Error('INVALID_PARTS');
  return {
    ...header,
    part_count: parts.length,
    parts
  };
}

function randomShortResourceId() {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
  let id = '';
  while (id.length < 5) {
    for (const value of crypto.randomBytes(8)) {
      if (value < 234) id += alphabet[value % alphabet.length];
      if (id.length === 5) break;
    }
  }
  return id;
}

async function createResource(db, params) {
  const value = validateCreate(params);
  const idFormat = params && params.idFormat == null ? 'legacyHex8' : params.idFormat;
  if (idFormat !== 'legacyHex8' && idFormat !== 'letters5') throw new Error('INVALID_ID_FORMAT');
  const collection = db.collection(RESOURCE_COLLECTION);
  const now = Date.now();
  for (let attempt = 0; attempt < 50; attempt += 1) {
    const id = idFormat === 'letters5' ? randomShortResourceId() : crypto.randomBytes(4).toString('hex');
    const existing = await collection.where({ resource_id: id }).limit(1).get();
    if (existing.data && existing.data.length > 0) continue;
    try {
      await collection.add({
        resource_id: id,
        ...value,
        created_at: now,
        ...(idFormat === 'letters5' ? { expires_at: now + SHORT_ID_TTL_MS } : {})
      });
      return { id, ...(idFormat === 'letters5' ? { expiresAt: now + SHORT_ID_TTL_MS } : {}) };
    } catch (error) {
      const collision = await collection.where({ resource_id: id }).limit(1).get();
      if (!collision.data || collision.data.length === 0) throw error;
    }
  }
  throw new Error('ID_GENERATION_FAILED');
}

function draftTokenHash(token) {
  return crypto.createHash('sha256').update(token, 'utf8').digest('hex');
}

async function requireDraft(db, params) {
  if (!params || typeof params.id !== 'string' || !/^[A-Z]{5}$/.test(params.id) ||
      typeof params.draftToken !== 'string' || params.draftToken.length < 32 || params.draftToken.length > 128) throw new Error('INVALID_DRAFT');
  const found = await db.collection(RESOURCE_COLLECTION).where({ resource_id: params.id, status: 'draft' }).limit(1).get();
  const draft = found.data && found.data[0];
  if (!draft || draft.expires_at <= Date.now()) throw new Error('INVALID_DRAFT');
  const actual = Buffer.from(draftTokenHash(params.draftToken), 'hex');
  const expected = Buffer.from(draft.draft_token_hash || '', 'hex');
  if (actual.length !== expected.length || !crypto.timingSafeEqual(actual, expected)) throw new Error('INVALID_DRAFT');
  return draft;
}

async function createResourceDraft(db, params) {
  const header = validateResourceHeader(params, false);
  const expectedPartCount = params.expectedPartCount;
  if (!Number.isSafeInteger(expectedPartCount) || expectedPartCount < 1 || expectedPartCount > MAX_PARTS) throw new Error('INVALID_PARTS');
  const idFormat = params.idFormat == null ? 'letters5' : params.idFormat;
  if (idFormat !== 'letters5') throw new Error('INVALID_ID_FORMAT');
  const collection = db.collection(RESOURCE_COLLECTION);
  const now = Date.now();
  for (let attempt = 0; attempt < 50; attempt += 1) {
    const id = randomShortResourceId();
    const existing = await collection.where({ resource_id: id }).limit(1).get();
    if (existing.data && existing.data.length > 0) continue;
    const draftToken = crypto.randomBytes(32).toString('base64url');
    try {
      await collection.add({
        resource_id: id,
        ...header,
        part_count: expectedPartCount,
        parts: [],
        parts_batched: true,
        status: 'draft',
        draft_token_hash: draftTokenHash(draftToken),
        created_at: now,
        expires_at: now + DRAFT_TTL_MS
      });
      return { id, draftToken, expectedPartCount, batchSize: PARTS_PER_BATCH, expiresAt: now + DRAFT_TTL_MS };
    } catch (error) {
      const collision = await collection.where({ resource_id: id }).limit(1).get();
      if (!collision.data || collision.data.length === 0) throw error;
    }
  }
  throw new Error('ID_GENERATION_FAILED');
}

async function addResourcePartBatch(db, params) {
  const draft = await requireDraft(db, params);
  const startIndex = params.startIndex;
  if (!Number.isSafeInteger(startIndex) || startIndex < 0 || startIndex % PARTS_PER_BATCH !== 0) throw new Error('INVALID_PARTS');
  const parts = validateParts(params.parts, startIndex, PARTS_PER_BATCH);
  if (draft.protocol_version === 1 && parts.some(part => part.cloudSource)) throw new Error('INVALID_PROTOCOL');
  if (startIndex + parts.length > draft.part_count ||
      (startIndex + parts.length < draft.part_count && parts.length !== PARTS_PER_BATCH)) throw new Error('INVALID_PARTS');
  const collection = db.collection(RESOURCE_PART_BATCH_COLLECTION);
  const digest = crypto.createHash('sha256').update(JSON.stringify(parts)).digest('hex');
  const existing = await collection.where({ resource_id: draft.resource_id, start_index: startIndex }).limit(1).get();
  if (existing.data && existing.data.length > 0) {
    if (existing.data[0].batch_sha256 !== digest) throw new Error('PART_BATCH_CONFLICT');
    return { id: draft.resource_id, startIndex, accepted: parts.length, nextStart: startIndex + parts.length };
  }
  await collection.add({
    resource_id: draft.resource_id,
    start_index: startIndex,
    part_count: parts.length,
    batch_sha256: digest,
    parts,
    created_at: Date.now(),
    expires_at: draft.expires_at
  });
  return { id: draft.resource_id, startIndex, accepted: parts.length, nextStart: startIndex + parts.length };
}

async function listPartBatches(db, resourceId) {
  const collection = db.collection(RESOURCE_PART_BATCH_COLLECTION);
  const batches = [];
  for (let offset = 0; ; offset += 100) {
    const page = await collection.where({ resource_id: resourceId }).orderBy('start_index', 'asc').skip(offset).limit(100).get();
    const rows = page.data || [];
    batches.push(...rows);
    if (rows.length < 100) break;
    if (batches.length > Math.ceil(MAX_PARTS / PARTS_PER_BATCH)) throw new Error('INVALID_PARTS');
  }
  return batches;
}

async function finalizeResourceDraft(db, params) {
  const draft = await requireDraft(db, params);
  const batches = await listPartBatches(db, draft.resource_id);
  let nextIndex = 0;
  for (const batch of batches) {
    if (batch.start_index !== nextIndex || !Array.isArray(batch.parts) || batch.parts.length !== batch.part_count) throw new Error('PARTS_INCOMPLETE');
    validateParts(batch.parts, nextIndex, PARTS_PER_BATCH);
    nextIndex += batch.parts.length;
  }
  if (nextIndex !== draft.part_count) throw new Error('PARTS_INCOMPLETE');
  const expiresAt = Date.now() + SHORT_ID_TTL_MS;
  await db.collection(RESOURCE_COLLECTION).doc(draft._id).update({ status: 'active', finalized_at: Date.now(), expires_at: expiresAt });
  await db.collection(RESOURCE_PART_BATCH_COLLECTION).where({ resource_id: draft.resource_id }).update({ expires_at: expiresAt });
  return { id: draft.resource_id, expiresAt };
}

async function getResourceParts(db, params) {
  if (!params || typeof params.id !== 'string' || !/^[A-Z]{5}$/.test(params.id) ||
      !Number.isSafeInteger(params.startIndex) || params.startIndex < 0 || params.startIndex % PARTS_PER_BATCH !== 0) throw new Error('NOT_FOUND');
  const resourceResult = await db.collection(RESOURCE_COLLECTION).where({ resource_id: params.id, status: 'active', parts_batched: true }).limit(1).get();
  const resource = resourceResult.data && resourceResult.data[0];
  if (!resource || resource.expires_at <= Date.now()) throw new Error('NOT_FOUND');
  const found = await db.collection(RESOURCE_PART_BATCH_COLLECTION).where({ resource_id: params.id, start_index: params.startIndex }).limit(1).get();
  const batch = found.data && found.data[0];
  if (!batch) throw new Error('NOT_FOUND');
  const parts = validateParts(batch.parts, params.startIndex, PARTS_PER_BATCH);
  const nextStart = params.startIndex + parts.length;
  return { id: params.id, startIndex: params.startIndex, parts, nextStart: nextStart < resource.part_count ? nextStart : null };
}

async function getResource(db, params) {
  // Invalid and absent identifiers deliberately produce the same response.
  if (!params || typeof params.id !== 'string') throw new Error('NOT_FOUND');
  const rawId = params.id.trim();
  const id = /^[a-z]{5}$/i.test(rawId)
    ? rawId.toUpperCase()
    : (/^[0-9a-f]{8}$/i.test(rawId) ? rawId.toLowerCase() : null);
  if (!id) throw new Error('NOT_FOUND');
  const found = await db.collection(RESOURCE_COLLECTION).where({ resource_id: id }).limit(1).get();
  const record = found.data && found.data[0];
  if (!record) throw new Error('NOT_FOUND');
  if (Number.isFinite(record.expires_at) && record.expires_at <= Date.now()) throw new Error('NOT_FOUND');
  if (record.status === 'draft') throw new Error('NOT_FOUND');
  return {
    id: record.resource_id,
    protocolVersion: record.protocol_version,
    fileKey: record.file_key,
    encryptedMetadata: record.encrypted_metadata,
    originalSha256: record.original_sha256,
    partCount: record.part_count,
    parts: record.parts || [],
    partsPaged: record.parts_batched === true,
    partBatchSize: record.parts_batched === true ? PARTS_PER_BATCH : undefined
  };
}

async function removeCollectionBatches(db, name, predicate) {
  const collection = db.collection(name);
  let deleted = 0;
  for (let batch = 0; batch < CLEANUP_MAX_BATCHES; batch += 1) {
    const query = predicate ? collection.where(predicate) : collection;
    const found = await query.limit(CLEANUP_BATCH_SIZE).get();
    const records = found.data || [];
    if (records.length === 0) break;
    for (const record of records) {
      await collection.doc(record._id).remove();
      deleted += 1;
    }
    if (records.length < CLEANUP_BATCH_SIZE) break;
  }
  return deleted;
}

async function scheduledCleanup(db) {
  const now = Date.now();
  const expiredResources = db.command.lte
    ? await removeCollectionBatches(db, RESOURCE_COLLECTION, { expires_at: db.command.lte(now) })
    : 0;
  const expiredPartBatches = db.command.lte
    ? await removeCollectionBatches(db, RESOURCE_PART_BATCH_COLLECTION, { expires_at: db.command.lte(now) })
    : 0;
  const expiredLimits = await removeCollectionBatches(db, RATE_LIMIT_COLLECTION, db.command.lte
    ? { expires_at: db.command.lte(now) }
    : null);
  return { expiredResources, expiredPartBatches, expiredLimits };
}

function errorResult(error) {
  const rawCode = error && error.message ? error.message : 'INTERNAL_ERROR';
  const code = rawCode.split(':')[0].replace(/^UPDATE_SOURCE_HTTP_\d+$/, 'UPDATE_SOURCE_HTTP_ERROR');
  const known = {
    INVALID_JSON: [400, '请求内容不是有效 JSON'],
    REQUEST_TOO_LARGE: [413, '请求内容过大'],
    INVALID_REQUEST: [400, '请求格式错误'],
    INVALID_PROTOCOL: [400, '协议版本不受支持'],
    INVALID_ID_FORMAT: [400, '口令码格式无效'],
    INVALID_KEY: [400, '加密参数无效'],
    INVALID_HASH: [400, '完整性参数无效'],
    INVALID_METADATA: [400, '加密内容无效或超过 1000 字节限制'],
    INVALID_PARTS: [400, '分片信息无效'],
    INVALID_DRAFT: [404, '资源草稿不存在、已过期或凭据无效'],
    PART_BATCH_CONFLICT: [409, '同一分片批次已提交不同内容'],
    PARTS_INCOMPLETE: [409, '资源分片尚未完整提交'],
    INVALID_URL: [400, '仅支持规范的 HTTPS 直链'],
    NOT_FOUND: [404, '对应内容不存在'],
    RATE_LIMITED: [429, '请求过于频繁，请稍后再试'],
    RATE_LIMIT_NOT_CONFIGURED: [500, '服务器安全配置不完整'],
    RATE_LIMIT_STORE_FAILED: [500, '服务器安全状态写入失败'],
    ID_GENERATION_FAILED: [503, '暂时无法创建，请稍后重试'],
    UPDATE_ADMIN_DENIED: [403, '更新发布鉴权失败'],
    UPDATE_STORAGE_NOT_CONFIGURED: [503, '更新对象存储尚未完整配置'],
    INVALID_UPDATE_OBJECT_KEY: [400, '更新对象路径无效'],
    INVALID_UPDATE_BYTES: [400, '更新文件内容无效'],
    INVALID_UPDATE_CHANNEL: [400, '更新通道无效'],
    INVALID_UPDATE_MANIFEST: [400, '更新清单无效'],
    INVALID_UPDATE_SOURCE: [400, '更新源地址无效'],
    UPDATE_SOURCE_HTTP_ERROR: [502, '无法从更新源下载文件'],
    UPDATE_SOURCE_SIZE_MISMATCH: [400, '更新源文件大小不一致'],
    UPDATE_SOURCE_HASH_MISMATCH: [400, '更新源文件哈希不一致'],
    UPDATE_REPLICATION_FAILED: [502, '更新文件写入 uniCloud 对象存储失败'],
    UPDATE_VERSION_EXISTS: [409, '该版本已经发布'],
    UPDATE_VERSION_NOT_FOUND: [404, '该更新版本不存在'],
    UPDATE_COS_FULL_REPLICA_REQUIRED: [409, '完整安装包缺少唯一的腾讯 COS 副本'],
    UPDATE_DELETE_ARTIFACT_MISMATCH: [409, '更新版本哈希不匹配，拒绝删除'],
    INVALID_FALLBACK: [400, '备用下载线路内容无效'],
    INVALID_FALLBACK_OPERATION: [400, '备用下载线路操作无效'],
    METHOD_NOT_ALLOWED: [405, '只允许 POST 请求'],
    UNKNOWN_ACTION: [404, '未知操作']
  };
  const mapped = known[code] || [500, '服务器处理失败'];
  return jsonResponse(mapped[0], { ok: false, code, message: mapped[1] });
}

exports.main = async (event, context) => {
  if (context && context.SOURCE === 'timing') {
    try {
      const result = await scheduledCleanup(uniCloud.database());
      console.log('TrustMark scheduled cleanup completed:', result);
      return result;
    } catch (error) {
      console.error('TrustMark scheduled cleanup failed:', error && error.message ? error.message : error);
      throw error;
    }
  }
  if (event && event.httpMethod === 'OPTIONS') return jsonResponse(204, {});
  try {
    if (event && event.httpMethod && event.httpMethod !== 'POST') throw new Error('METHOD_NOT_ALLOWED');
    const params = parseRequest(event);
    if (!VALID_ACTIONS.has(params.action)) throw new Error('UNKNOWN_ACTION');
    const db = uniCloud.database();
    await enforceRateLimit(db, context && context.CLIENTIP, params.action);
    const data = params.action === 'tm-create'
      ? await createResource(db, params)
      : params.action === 'tm-create-draft'
        ? await createResourceDraft(db, params)
      : params.action === 'tm-create-parts'
        ? await addResourcePartBatch(db, params)
      : params.action === 'tm-create-finalize'
        ? await finalizeResourceDraft(db, params)
      : params.action === 'tm-get'
        ? await getResource(db, params)
      : params.action === 'tm-get-parts'
        ? await getResourceParts(db, params)
        : params.action === 'tm-download-android'
          ? await currentAndroidDownload(db)
        : params.action === 'tm-download-fallbacks'
          ? await downloadFallbacks(db)
        : params.action === 'tm-download-fallbacks-admin'
          ? await manageDownloadFallbacks(db, params)
        : params.action === 'tm-update-publish'
          ? await publishUpdate(db, params)
        : params.action === 'tm-update-publish-builtin-patch'
          ? await publishBuiltinPatchUpdate(db, params)
        : params.action === 'tm-update-repair'
          ? await repairUpdate(db, params)
        : params.action === 'tm-update-delete'
          ? await deleteUpdate(db, params)
        : params.action === 'tm-update-normalize-storage'
          ? await normalizeUpdateStorage(db, params)
          : await currentVersion(db, params);
    return jsonResponse(200, { ok: true, data });
  } catch (error) {
    console.error('TrustMark API error:', error && error.message ? error.message : error);
    return errorResult(error);
  }
};

if (process.env.NODE_ENV === 'test') {
  exports._test = { validatePart, validateParts, validateResourceHeader, draftTokenHash, isBilibiliPart };
}
