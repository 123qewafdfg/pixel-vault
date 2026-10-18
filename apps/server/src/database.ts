import { randomBytes } from "node:crypto";
import { DatabaseSync } from "node:sqlite";

import { encodeResourceId } from "./encoding.ts";
import type { ResourcePartInput, StoredResource } from "./types.ts";

export const MAX_PARTS = 25;

interface ResourceWrite {
  protocolVersion: number;
  encryptedFileKey: Uint8Array;
  keyIv: Uint8Array;
  encryptedMetadata: string;
  originalSha256: string;
  parts: ResourcePartInput[];
}

type IdGenerator = () => Uint8Array;

export class ResourceRepository {
  readonly database: DatabaseSync;
  private readonly generateId: IdGenerator;

  constructor(filename: string, generateId: IdGenerator = () => new Uint8Array(randomBytes(4))) {
    this.generateId = generateId;
    this.database = new DatabaseSync(filename);
    this.database.exec("PRAGMA foreign_keys = ON; PRAGMA journal_mode = WAL; PRAGMA busy_timeout = 5000;");
    this.migrate();
  }

  private migrate(): void {
    const versionRow = this.database.prepare("PRAGMA user_version").get() as { user_version: number };
    if (versionRow.user_version > 1) {
      throw new Error(`database schema version ${versionRow.user_version} is newer than supported version 1`);
    }
    if (versionRow.user_version === 1) return;
    this.database.exec("BEGIN IMMEDIATE");
    try {
      this.database.exec(`
      CREATE TABLE IF NOT EXISTS resources (
        id BLOB PRIMARY KEY CHECK(length(id) = 4),
        protocol_version INTEGER NOT NULL CHECK(protocol_version BETWEEN 0 AND 255),
        encrypted_file_key BLOB NOT NULL CHECK(length(encrypted_file_key) = 48),
        key_iv BLOB NOT NULL CHECK(length(key_iv) = 12),
        encrypted_metadata TEXT NOT NULL,
        original_sha256 TEXT NOT NULL CHECK(length(original_sha256) = 64),
        part_count INTEGER NOT NULL CHECK(part_count BETWEEN 1 AND 25),
        status TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('active', 'disabled')),
        created_at TEXT NOT NULL
      ) STRICT;
      CREATE TABLE IF NOT EXISTS resource_parts (
        resource_id BLOB NOT NULL,
        part_index INTEGER NOT NULL CHECK(part_index BETWEEN 0 AND 24),
        download_url TEXT NOT NULL,
        remote_sha256 TEXT NOT NULL CHECK(length(remote_sha256) = 64),
        cipher_sha256 TEXT NOT NULL CHECK(length(cipher_sha256) = 64),
        cipher_size INTEGER NOT NULL CHECK(cipher_size >= 0),
        created_at TEXT NOT NULL,
        PRIMARY KEY(resource_id, part_index),
        FOREIGN KEY(resource_id) REFERENCES resources(id) ON DELETE CASCADE
      ) STRICT;
      `);
      this.database.exec("PRAGMA user_version = 1; COMMIT");
    } catch (error) {
      this.database.exec("ROLLBACK");
      throw error;
    }
  }

  create(input: ResourceWrite): Uint8Array {
    const insertResource = this.database.prepare(`
      INSERT INTO resources (
        id, protocol_version, encrypted_file_key, key_iv, encrypted_metadata,
        original_sha256, part_count, status, created_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, 'active', ?)
    `);
    const insertPart = this.database.prepare(`
      INSERT INTO resource_parts (
        resource_id, part_index, download_url, remote_sha256, cipher_sha256, cipher_size, created_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?)
    `);
    const exists = this.database.prepare("SELECT 1 FROM resources WHERE id = ?");

    for (let attempt = 0; attempt < 128; attempt += 1) {
      const id = this.generateId();
      if (id.byteLength !== 4) throw new Error("ID generator returned an invalid value");
      if (exists.get(Buffer.from(id)) !== undefined) continue;
      const now = new Date().toISOString();
      this.database.exec("BEGIN IMMEDIATE");
      try {
        try {
          insertResource.run(
            Buffer.from(id), input.protocolVersion, Buffer.from(input.encryptedFileKey), Buffer.from(input.keyIv),
            input.encryptedMetadata, input.originalSha256, Math.max(1, input.parts.length), now,
          );
        } catch (error) {
          this.database.exec("ROLLBACK");
          if (error instanceof Error && error.message.includes("UNIQUE constraint failed: resources.id")) continue;
          throw error;
        }
        for (const part of input.parts) {
          insertPart.run(
            Buffer.from(id), part.partIndex, part.downloadUrl, part.remoteSha256,
            part.cipherSha256, part.cipherSize, now,
          );
        }
        this.database.exec("COMMIT");
        return id;
      } catch (error) {
        if (this.database.isTransaction) this.database.exec("ROLLBACK");
        throw error;
      }
    }
    throw new Error("unable to allocate a unique resource ID");
  }

  findActive(id: Uint8Array): StoredResource | null {
    const resource = this.database.prepare(`
      SELECT protocol_version, encrypted_file_key, key_iv, encrypted_metadata,
             original_sha256, part_count, status, created_at
      FROM resources WHERE id = ? AND status = 'active'
    `).get(Buffer.from(id)) as Record<string, unknown> | undefined;
    if (resource === undefined) return null;
    const parts = this.database.prepare(`
      SELECT part_index, download_url, remote_sha256, cipher_sha256, cipher_size
      FROM resource_parts WHERE resource_id = ? ORDER BY part_index ASC
    `).all(Buffer.from(id)) as Record<string, unknown>[];
    return {
      id,
      protocolVersion: Number(resource.protocol_version),
      encryptedFileKey: new Uint8Array(resource.encrypted_file_key as Uint8Array),
      keyIv: new Uint8Array(resource.key_iv as Uint8Array),
      encryptedMetadata: String(resource.encrypted_metadata),
      originalSha256: String(resource.original_sha256),
      partCount: Number(resource.part_count),
      status: String(resource.status) as "active" | "disabled",
      createdAt: String(resource.created_at),
      parts: parts.map((part) => ({
        partIndex: Number(part.part_index),
        downloadUrl: String(part.download_url),
        remoteSha256: String(part.remote_sha256),
        cipherSha256: String(part.cipher_sha256),
        cipherSize: Number(part.cipher_size),
      })),
    };
  }

  setStatus(id: Uint8Array, status: "active" | "disabled"): void {
    this.database.prepare("UPDATE resources SET status = ? WHERE id = ?").run(status, Buffer.from(id));
  }

  close(): void {
    this.database.close();
  }

  describeId(id: Uint8Array): string {
    return encodeResourceId(id);
  }
}
