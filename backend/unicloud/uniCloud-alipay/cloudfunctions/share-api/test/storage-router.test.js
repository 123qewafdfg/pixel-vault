'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

test('new update patches use only uniCloud builtin storage', async () => {
  const previousUniCloud = global.uniCloud;
  const calls = { upload: [], temp: [], remove: [] };
  global.uniCloud = {
    async uploadFile(options) {
      calls.upload.push(options);
      return { fileID: `cloud://${options.cloudPath}` };
    },
    async getTempFileURL(options) {
      calls.temp.push(options);
      return { fileList: [{ fileID: options.fileList[0], tempFileURL: 'https://example.invalid/patch' }] };
    },
    async deleteFile(options) {
      calls.remove.push(options);
      return {};
    }
  };

  try {
    const router = require('../storage-router');
    assert.deepEqual(router.FULL_BACKENDS, []);
    assert.deepEqual(router.PATCH_BACKENDS, ['builtin']);

    const references = await router.replicatePatch('v16/from-v15.tmdelta.gz', Buffer.from('patch'), 'application/gzip');
    assert.equal(references.length, 1);
    assert.equal(references[0].backend, 'builtin');
    assert.equal(calls.upload.length, 1);

    const mirrors = await router.patchUrls(references);
    assert.deepEqual(mirrors, [{ backend: 'builtin', url: 'https://example.invalid/patch' }]);

    await router.removeReplicas([...references, { backend: 'cos', objectKey: 'legacy' }]);
    assert.equal(calls.remove.length, 1);
  } finally {
    global.uniCloud = previousUniCloud;
  }
});
