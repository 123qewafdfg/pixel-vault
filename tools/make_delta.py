#!/usr/bin/env python3
import argparse
import gzip
import hashlib
import json
import os
import struct
import tempfile

MAGIC = b"TMDLT001"
MIN_CHUNK = 16 * 1024
AVG_MASK = (64 * 1024) - 1
MAX_CHUNK = 256 * 1024
GEAR = [int.from_bytes(hashlib.sha256(bytes([i])).digest()[:8], "big") for i in range(256)]


def sha256(data):
    return hashlib.sha256(data).digest()


def chunks(data):
    start = 0
    size = len(data)
    while start < size:
        end = min(start + MAX_CHUNK, size)
        cursor = min(start + MIN_CHUNK, end)
        rolling = 0
        while cursor < end:
            rolling = ((rolling << 1) + GEAR[data[cursor]]) & 0xFFFFFFFFFFFFFFFF
            cursor += 1
            if rolling & AVG_MASK == 0:
                break
        yield start, cursor - start
        start = cursor


def merged_ops(old, new):
    index = {}
    for offset, length in chunks(old):
        index.setdefault((length, sha256(old[offset:offset + length])), []).append(offset)
    operations = []
    for offset, length in chunks(new):
        block = new[offset:offset + length]
        matches = index.get((length, sha256(block)), [])
        source = next((item for item in matches if old[item:item + length] == block), None)
        if source is not None:
            if operations and operations[-1][0] == 0 and operations[-1][1] + operations[-1][2] == source:
                operations[-1] = (0, operations[-1][1], operations[-1][2] + length)
            else:
                operations.append((0, source, length))
        elif operations and operations[-1][0] == 1:
            operations[-1] = (1, operations[-1][1] + block)
        else:
            operations.append((1, block))
    return operations


def write_patch(old, new, output):
    operations = merged_ops(old, new)
    with open(output, "wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=9, mtime=0) as stream:
            stream.write(MAGIC)
            stream.write(struct.pack(">QQ", len(old), len(new)))
            stream.write(sha256(old))
            stream.write(sha256(new))
            for operation in operations:
                if operation[0] == 0:
                    stream.write(struct.pack(">BQI", 0, operation[1], operation[2]))
                else:
                    stream.write(struct.pack(">BI", 1, len(operation[1])))
                    stream.write(operation[1])
            stream.write(b"\x7f")


def apply_for_test(old, patch):
    result = bytearray()
    with gzip.open(patch, "rb") as stream:
        assert stream.read(8) == MAGIC
        old_size, new_size = struct.unpack(">QQ", stream.read(16))
        old_hash, new_hash = stream.read(32), stream.read(32)
        assert old_size == len(old) and old_hash == sha256(old)
        while True:
            opcode = stream.read(1)
            if opcode == b"\x7f":
                break
            if opcode == b"\x00":
                source, length = struct.unpack(">QI", stream.read(12))
                result.extend(old[source:source + length])
            elif opcode == b"\x01":
                length = struct.unpack(">I", stream.read(4))[0]
                result.extend(stream.read(length))
            else:
                raise ValueError("unknown delta opcode")
        assert len(result) == new_size and sha256(result) == new_hash and not stream.read(1)
    return bytes(result)


def main():
    parser = argparse.ArgumentParser(description="Generate a TrustMark Share APK delta")
    parser.add_argument("old_apk")
    parser.add_argument("new_apk")
    parser.add_argument("output_patch")
    parser.add_argument("--from-version", type=int, required=True)
    parser.add_argument("--url", required=True)
    args = parser.parse_args()
    with open(args.old_apk, "rb") as stream:
        old = stream.read()
    with open(args.new_apk, "rb") as stream:
        new = stream.read()
    write_patch(old, new, args.output_patch)
    if apply_for_test(old, args.output_patch) != new:
        raise SystemExit("delta self-test failed")
    metadata = {
        "fromVersionCode": args.from_version,
        "url": args.url,
        "size": os.path.getsize(args.output_patch),
        "sha256": hashlib.sha256(open(args.output_patch, "rb").read()).hexdigest(),
        "baseApkSha256": hashlib.sha256(old).hexdigest(),
        "resultApkSha256": hashlib.sha256(new).hexdigest(),
    }
    print(json.dumps(metadata, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
