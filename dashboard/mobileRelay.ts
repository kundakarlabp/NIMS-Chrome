export type MobileBridgeState =
  | 'unpaired'
  | 'offline'
  | 'ready'
  | 'fetching'
  | 'auth_required'
  | 'error';

export interface MobileBridgePairing {
  deviceId: string;
  requesterToken: string;
  publicKeyJwk: JsonWebKey;
  pairedAt?: string;
}

export interface MobileBridgeJobStatus {
  id: string;
  status: 'queued' | 'claimed' | 'auth_required' | 'completed' | 'error' | 'expired';
  result_envelope?: EncryptedEnvelope | null;
  error_code?: string | null;
  requested_at?: string;
  claimed_at?: string | null;
  completed_at?: string | null;
  expires_at?: string;
}

interface EncryptedEnvelope {
  v: 1;
  alg: 'RSA-OAEP-256+A256GCM';
  wrappedKey: string;
  iv: string;
  ciphertext: string;
}

const RELAY_URL = 'https://dehdptgkqbrkyzyodicd.supabase.co/functions/v1/nims-chat-bridge';
const STORAGE_KEY = 'nims.mobileBridge.requester.v1';
const encoder = new TextEncoder();
const decoder = new TextDecoder();

function bytesToBase64Url(bytes: Uint8Array) {
  let binary = '';
  bytes.forEach(byte => { binary += String.fromCharCode(byte); });
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

function base64UrlToBytes(value: string) {
  const raw = value.replace(/-/g, '+').replace(/_/g, '/');
  const padded = raw + '='.repeat((4 - (raw.length % 4 || 4)) % 4);
  return Uint8Array.from(atob(padded), ch => ch.charCodeAt(0));
}

async function importPublicKey(jwk: JsonWebKey) {
  return crypto.subtle.importKey('jwk', jwk, { name: 'RSA-OAEP', hash: 'SHA-256' }, false, ['encrypt']);
}

async function importPrivateKey(jwk: JsonWebKey) {
  return crypto.subtle.importKey('jwk', jwk, { name: 'RSA-OAEP', hash: 'SHA-256' }, false, ['decrypt']);
}

async function encryptJson(value: unknown, publicKeyJwk: JsonWebKey): Promise<EncryptedEnvelope> {
  const publicKey = await importPublicKey(publicKeyJwk);
  const aesKey = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt']);
  const rawAes = new Uint8Array(await crypto.subtle.exportKey('raw', aesKey));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = new Uint8Array(
    await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, aesKey, encoder.encode(JSON.stringify(value)))
  );
  const wrappedKey = new Uint8Array(await crypto.subtle.encrypt({ name: 'RSA-OAEP' }, publicKey, rawAes));
  return {
    v: 1,
    alg: 'RSA-OAEP-256+A256GCM',
    wrappedKey: bytesToBase64Url(wrappedKey),
    iv: bytesToBase64Url(iv),
    ciphertext: bytesToBase64Url(ciphertext),
  };
}

async function decryptJson(envelope: EncryptedEnvelope, privateKeyJwk: JsonWebKey) {
  const privateKey = await importPrivateKey(privateKeyJwk);
  const rawAes = await crypto.subtle.decrypt(
    { name: 'RSA-OAEP' },
    privateKey,
    base64UrlToBytes(envelope.wrappedKey)
  );
  const aesKey = await crypto.subtle.importKey('raw', rawAes, { name: 'AES-GCM' }, false, ['decrypt']);
  const plain = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: base64UrlToBytes(envelope.iv) },
    aesKey,
    base64UrlToBytes(envelope.ciphertext)
  );
  return JSON.parse(decoder.decode(plain));
}

async function relayPost(body: Record<string, unknown>, pairing?: MobileBridgePairing) {
  const headers: Record<string, string> = { 'content-type': 'application/json' };
  if (pairing) {
    headers['x-nims-device-id'] = pairing.deviceId;
    headers['x-nims-requester-secret'] = pairing.requesterToken;
  }
  const response = await fetch(RELAY_URL, {
    method: 'POST',
    headers,
    cache: 'no-store',
    body: JSON.stringify(body),
  });
  const data = await response.json().catch(() => ({ ok: false, error: 'invalid_relay_response' }));
  if (!response.ok || !data?.ok) throw new Error(data?.error || 'relay_unavailable');
  return data;
}

export function loadMobilePairing(): MobileBridgePairing | null {
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    if (!value) return null;
    const parsed = JSON.parse(value) as MobileBridgePairing;
    if (!parsed.deviceId || !parsed.requesterToken || !parsed.publicKeyJwk) return null;
    return parsed;
  } catch {
    return null;
  }
}

export function clearMobilePairing() {
  localStorage.removeItem(STORAGE_KEY);
}

export async function pairMobileAgent(pairingCode: string): Promise<MobileBridgePairing> {
  const code = pairingCode.trim().toUpperCase().replace(/[^A-Z2-9]/g, '');
  if (!/^[A-HJ-NP-Z2-9]{8}$/.test(code)) throw new Error('Enter the 8-character pairing code shown by the NIMS Results app.');
  const data = await relayPost({ action: 'pair', pairingCode: code });
  const pairing: MobileBridgePairing = {
    deviceId: data.deviceId,
    requesterToken: data.requesterToken,
    publicKeyJwk: data.publicKeyJwk,
    pairedAt: data.pairedAt,
  };
  localStorage.setItem(STORAGE_KEY, JSON.stringify(pairing));
  return pairing;
}

export async function getMobileAgentStatus(pairing: MobileBridgePairing) {
  return relayPost({ action: 'device_status' }, pairing) as Promise<{
    ok: true;
    online: boolean;
    lastSeenAt?: string;
    clientVersion?: string;
  }>;
}

export async function requestCrFromMobileAgent(
  pairing: MobileBridgePairing,
  crNo: string,
  onState?: (state: MobileBridgeState, message: string) => void,
  timeoutMs = 10 * 60 * 1000
) {
  const cleaned = crNo.replace(/\D/g, '');
  if (!/^\d{15}$/.test(cleaned)) throw new Error('Enter the 15-digit NIMS CR number.');

  const resultPair = await crypto.subtle.generateKey(
    { name: 'RSA-OAEP', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' },
    true,
    ['encrypt', 'decrypt']
  );
  const resultPublicKeyJwk = await crypto.subtle.exportKey('jwk', resultPair.publicKey);
  const resultPrivateKeyJwk = await crypto.subtle.exportKey('jwk', resultPair.privateKey);
  const requestEnvelope = await encryptJson({ crNo: cleaned }, pairing.publicKeyJwk);

  onState?.('fetching', 'Sending encrypted CR request to the NIMS Android agent…');
  const created = await relayPost({ action: 'request', requestEnvelope, resultPublicKeyJwk }, pairing);
  const jobId = created.job.id as string;
  const started = Date.now();

  while (Date.now() - started < timeoutMs) {
    await new Promise(resolve => setTimeout(resolve, 1600));
    const data = await relayPost({ action: 'job_status', jobId }, pairing);
    const job = data.job as MobileBridgeJobStatus;

    if (job.status === 'queued') {
      onState?.('fetching', 'Request queued on the Android agent…');
      continue;
    }
    if (job.status === 'claimed') {
      onState?.('fetching', 'Android agent is retrieving NIMS results…');
      continue;
    }
    if (job.status === 'auth_required') {
      onState?.('auth_required', 'NIMS authentication required on your phone. Enter the fresh CAPTCHA and tap Authenticate; this CR will resume automatically.');
      continue;
    }
    if (job.status === 'completed' && job.result_envelope) {
      const result = await decryptJson(job.result_envelope, resultPrivateKeyJwk);
      onState?.('ready', 'Results retrieved from the authenticated Android NIMS session.');
      return result;
    }
    if (job.status === 'expired') throw new Error('The NIMS request expired. Retry after confirming the phone agent is online.');
    if (job.status === 'error') throw new Error(job.error_code || 'NIMS retrieval failed.');
  }
  throw new Error('Timed out waiting for the Android NIMS agent.');
}
