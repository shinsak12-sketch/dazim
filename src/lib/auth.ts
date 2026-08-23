/**
 * 환경변수 기반 소규모 계정 + HMAC 서명 쿠키 세션.
 * 미들웨어(edge)와 라우트 핸들러(node) 양쪽에서 돌아야 하므로 Web Crypto만 쓴다.
 */

export const SESSION_COOKIE = "toon_session";
export const SESSION_MAX_AGE = 60 * 60 * 24 * 30; // 30일

export type Account = { name: string; pin: string };

/** ACCOUNTS="남편:1234,아내:5678" 형식을 파싱한다. */
export function listAccounts(): Account[] {
  const raw = process.env.ACCOUNTS ?? "";
  return raw
    .split(",")
    .map((chunk) => chunk.trim())
    .filter(Boolean)
    .map((chunk) => {
      // PIN에 ':'이 들어갈 수 있으니 마지막 ':' 기준으로 자른다.
      const i = chunk.lastIndexOf(":");
      if (i <= 0) return null;
      const name = chunk.slice(0, i).trim();
      const pin = chunk.slice(i + 1).trim();
      return name && pin ? { name, pin } : null;
    })
    .filter((a): a is Account => a !== null);
}

export function accountNames(): string[] {
  return listAccounts().map((a) => a.name);
}

/** 길이 노출은 어쩔 수 없지만 내용 비교는 상수 시간으로. */
export function timingSafeEqual(a: string, b: string): boolean {
  const len = Math.max(a.length, b.length);
  let diff = a.length ^ b.length;
  for (let i = 0; i < len; i++) {
    diff |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  }
  return diff === 0;
}

export function verifyCredentials(name: string, pin: string): Account | null {
  let found: Account | null = null;
  // 존재하지 않는 이름이어도 비교를 수행해 응답 시간 차이를 줄인다.
  for (const acc of listAccounts()) {
    if (timingSafeEqual(acc.name, name) && timingSafeEqual(acc.pin, pin)) {
      found = acc;
    }
  }
  return found;
}

function secretKeyMaterial(): string {
  const s = process.env.AUTH_SECRET;
  if (!s || s.length < 16) {
    throw new Error("AUTH_SECRET 환경변수가 없거나 너무 짧습니다 (16자 이상).");
  }
  return s;
}

const encoder = new TextEncoder();
let keyPromise: Promise<CryptoKey> | null = null;

function hmacKey(): Promise<CryptoKey> {
  if (!keyPromise) {
    keyPromise = crypto.subtle
      .importKey("raw", encoder.encode(secretKeyMaterial()), { name: "HMAC", hash: "SHA-256" }, false, [
        "sign",
        "verify",
      ])
      .catch((e) => {
        keyPromise = null;
        throw e;
      });
  }
  return keyPromise;
}

function b64urlEncode(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

// 반환 타입은 추론에 맡긴다. 명시하면 Uint8Array<ArrayBufferLike>로 넓어져 BufferSource와 안 맞는다.
function b64urlDecode(s: string) {
  const norm = s.replace(/-/g, "+").replace(/_/g, "/");
  const padded = norm + "=".repeat((4 - (norm.length % 4)) % 4);
  const bin = atob(padded);
  // ArrayBuffer로 명시해야 crypto.subtle의 BufferSource 타입과 맞는다.
  const out = new Uint8Array(new ArrayBuffer(bin.length));
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

type Payload = { u: string; e: number };

export async function createSession(name: string): Promise<string> {
  const payload: Payload = { u: name, e: Date.now() + SESSION_MAX_AGE * 1000 };
  const body = b64urlEncode(encoder.encode(JSON.stringify(payload)));
  const sig = await crypto.subtle.sign("HMAC", await hmacKey(), encoder.encode(body));
  return `${body}.${b64urlEncode(new Uint8Array(sig))}`;
}

/** 서명과 만료를 검증하고 계정 이름을 돌려준다. 실패하면 null. */
export async function readSession(token: string | undefined | null): Promise<string | null> {
  if (!token) return null;
  const dot = token.lastIndexOf(".");
  if (dot <= 0) return null;
  const body = token.slice(0, dot);
  const sig = token.slice(dot + 1);

  let ok = false;
  try {
    ok = await crypto.subtle.verify("HMAC", await hmacKey(), b64urlDecode(sig), encoder.encode(body));
  } catch {
    return null;
  }
  if (!ok) return null;

  try {
    const payload = JSON.parse(new TextDecoder().decode(b64urlDecode(body))) as Payload;
    if (typeof payload.u !== "string" || typeof payload.e !== "number") return null;
    if (Date.now() > payload.e) return null;
    // 환경변수에서 계정이 삭제됐다면 세션도 무효
    if (!accountNames().includes(payload.u)) return null;
    return payload.u;
  } catch {
    return null;
  }
}
