import browser from 'webextension-polyfill';

import {
  type AccountSummary,
  type AuthSession,
  type ExtensionSettings,
  AuthSessionSchema,
} from '../../shared/validation/models';

const INSTALL_SECRET_KEY = 'auth.installSecret';
const PERSISTENT_PAT_KEY = 'auth.persistentPat';
const SESSION_PAT_KEY = 'auth.sessionPat';
const AUTH_SESSION_KEY = 'auth.sessionMeta';
const SALT = 'arguslog-browser-extension';

interface EncryptedPayload {
  iv: string;
  ciphertext: string;
}

function encodeUtf8(value: string): ArrayBuffer {
  return new TextEncoder().encode(value).buffer;
}

function toBase64(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes));
}

function fromBase64(value: string): ArrayBuffer {
  return Uint8Array.from(atob(value), (char) => char.charCodeAt(0)).buffer;
}

async function getInstallSecret(): Promise<string> {
  const existing = (await browser.storage.local.get(INSTALL_SECRET_KEY))[INSTALL_SECRET_KEY];
  if (typeof existing === 'string' && existing.length > 0) {
    return existing;
  }

  const bytes = crypto.getRandomValues(new Uint8Array(32));
  const secret = toBase64(bytes);
  await browser.storage.local.set({ [INSTALL_SECRET_KEY]: secret });
  return secret;
}

async function deriveKey(): Promise<CryptoKey> {
  const secret = await getInstallSecret();
  const baseKey = await crypto.subtle.importKey('raw', encodeUtf8(secret), 'PBKDF2', false, [
    'deriveKey',
  ]);

  return crypto.subtle.deriveKey(
    {
      name: 'PBKDF2',
      salt: encodeUtf8(SALT),
      iterations: 100_000,
      hash: 'SHA-256',
    },
    baseKey,
    {
      name: 'AES-GCM',
      length: 256,
    },
    false,
    ['encrypt', 'decrypt'],
  );
}

async function encryptPat(pat: string): Promise<EncryptedPayload> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await deriveKey();
  const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, encodeUtf8(pat));
  return {
    iv: toBase64(iv),
    ciphertext: toBase64(new Uint8Array(ciphertext)),
  };
}

async function decryptPat(payload: EncryptedPayload): Promise<string> {
  const key = await deriveKey();
  const plaintext = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: new Uint8Array(fromBase64(payload.iv)) },
    key,
    fromBase64(payload.ciphertext),
  );
  return new TextDecoder().decode(plaintext);
}

export async function savePat(
  pat: string,
  settings: ExtensionSettings,
  accountSummary: AccountSummary,
): Promise<AuthSession> {
  const encrypted = await encryptPat(pat);
  if (settings.persistenceMode === 'persistent') {
    await browser.storage.local.set({ [PERSISTENT_PAT_KEY]: encrypted });
    await browser.storage.session.remove(SESSION_PAT_KEY);
  } else {
    await browser.storage.session.set({ [SESSION_PAT_KEY]: encrypted });
    await browser.storage.local.remove(PERSISTENT_PAT_KEY);
  }

  const authSession = AuthSessionSchema.parse({
    patPresent: true,
    persistenceMode: settings.persistenceMode,
    accountSummary,
  });

  await browser.storage.local.set({ [AUTH_SESSION_KEY]: authSession });
  return authSession;
}

export async function switchPersistenceMode(
  nextMode: ExtensionSettings['persistenceMode'],
): Promise<void> {
  const pat = await getPat();
  const session = await getAuthSession();
  if (!pat || !session) {
    return;
  }
  await savePat(
    pat,
    {
      endpoint: 'https://mcp.arguslog.org/mcp',
      persistenceMode: nextMode,
      debug: false,
      theme: 'system',
    },
    session.accountSummary!,
  );
}

export async function getPat(): Promise<string | undefined> {
  const sessionPayload = (await browser.storage.session.get(SESSION_PAT_KEY))[SESSION_PAT_KEY];
  const localPayload = (await browser.storage.local.get(PERSISTENT_PAT_KEY))[PERSISTENT_PAT_KEY];
  const payload = (sessionPayload ?? localPayload) as EncryptedPayload | undefined;

  if (!payload?.iv || !payload.ciphertext) {
    return undefined;
  }

  return decryptPat(payload);
}

export async function getAuthSession(): Promise<AuthSession | undefined> {
  const raw = (await browser.storage.local.get(AUTH_SESSION_KEY))[AUTH_SESSION_KEY];
  const parsed = AuthSessionSchema.safeParse(raw);
  return parsed.success ? parsed.data : undefined;
}

export async function clearPat(): Promise<void> {
  await browser.storage.local.remove([PERSISTENT_PAT_KEY, AUTH_SESSION_KEY]);
  await browser.storage.session.remove(SESSION_PAT_KEY);
}
