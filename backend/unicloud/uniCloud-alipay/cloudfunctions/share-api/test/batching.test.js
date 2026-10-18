'use strict';

process.env.NODE_ENV = 'test';
const test = require('node:test');
const assert = require('node:assert/strict');
const { validateParts, validateResourceHeader, draftTokenHash } = require('../index.js')._test;

const hash = 'a'.repeat(64);
function part(index, host = 'images.example.com') {
  return {
    partIndex: index,
    downloadUrl: `https://${host}/${index}.png`,
    remoteSha256: hash,
    cipherSha256: 'b'.repeat(64),
    cipherSize: 1024
  };
}

test('ordinary resources may exceed the former 25-part limit', () => {
  const value = Array.from({ length: 100 }, (_, index) => part(index));
  assert.equal(validateParts(value).length, 100);
});

test('Bilibili keeps its dedicated 64-part limit', () => {
  const value = Array.from({ length: 65 }, (_, index) => part(index, 'i0.hdslb.com'));
  assert.throws(() => validateParts(value), /INVALID_PARTS/);
});

test('batches use continuous global indexes', () => {
  const value = Array.from({ length: 50 }, (_, offset) => part(100 + offset));
  assert.equal(validateParts(value, 100, 50).at(-1).partIndex, 149);
  assert.throws(() => validateParts([part(0)], 100, 50), /INVALID_PARTS/);
});

test('draft resource headers use the same cryptographic validation', () => {
  const header = validateResourceHeader({
    protocolVersion: 1,
    fileKey: Buffer.alloc(32).toString('base64url'),
    encryptedMetadata: Buffer.from('metadata').toString('base64url'),
    originalSha256: hash
  }, false);
  assert.equal(header.protocol_version, 1);
  assert.throws(() => validateResourceHeader({ ...header, protocolVersion: 2 }, false), /INVALID_/);
});

test('draft token hashing is deterministic without storing the token', () => {
  assert.equal(draftTokenHash('x'.repeat(32)), draftTokenHash('x'.repeat(32)));
  assert.notEqual(draftTokenHash('x'.repeat(32)), draftTokenHash('y'.repeat(32)));
});
