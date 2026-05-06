import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { parseTag, readPackageVersion, verify } from './verify-tag-version.mjs';

describe('parseTag', () => {
  it('extracts a plain semver', () => {
    expect(parseTag('sdk-browser-v0.1.0', 'sdk-browser')).toBe('0.1.0');
  });

  it('extracts a prerelease semver', () => {
    expect(parseTag('java-sdk-v1.2.3-rc.4', 'java-sdk')).toBe('1.2.3-rc.4');
  });

  it('rejects a tag with the wrong prefix', () => {
    expect(() => parseTag('java-sdk-v0.1.0', 'sdk-browser')).toThrow(/required prefix/);
  });

  it('rejects a non-semver suffix', () => {
    expect(() => parseTag('sdk-browser-vlatest', 'sdk-browser')).toThrow(/not valid semver/);
  });
});

describe('readPackageVersion', () => {
  let tmpDir;
  beforeEach(() => {
    tmpDir = mkdtempSync(join(tmpdir(), 'argus-pkg-'));
  });
  afterEach(() => {
    rmSync(tmpDir, { recursive: true });
  });

  it('reads version from a package.json', () => {
    const path = join(tmpDir, 'package.json');
    writeFileSync(path, JSON.stringify({ name: '@arguslog/sdk-browser', version: '0.1.0' }));
    expect(readPackageVersion(path)).toBe('0.1.0');
  });

  it('throws when the version field is missing', () => {
    const path = join(tmpDir, 'package.json');
    writeFileSync(path, JSON.stringify({ name: '@arguslog/sdk-browser' }));
    expect(() => readPackageVersion(path)).toThrow(/No "version" field/);
  });
});

describe('verify (end-to-end)', () => {
  let tmpDir;
  beforeEach(() => {
    tmpDir = mkdtempSync(join(tmpdir(), 'argus-pkg-'));
  });
  afterEach(() => {
    rmSync(tmpDir, { recursive: true });
  });

  it('returns the version when tag and manifest agree', () => {
    const path = join(tmpDir, 'package.json');
    writeFileSync(path, JSON.stringify({ version: '1.0.0' }));
    expect(verify('sdk-browser-v1.0.0', 'sdk-browser', path)).toBe('1.0.0');
  });

  it('throws when tag and manifest disagree', () => {
    const path = join(tmpDir, 'package.json');
    writeFileSync(path, JSON.stringify({ version: '1.0.0' }));
    expect(() => verify('sdk-browser-v1.0.1', 'sdk-browser', path)).toThrow(/does not match/);
  });
});
