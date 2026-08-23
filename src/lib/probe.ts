import dns from "node:dns/promises";
import { isValidHostname } from "./site";

/**
 * 서버가 외부로 요청을 보내는 공용 유틸.
 * 호출부가 임의 주소를 넣지 못하도록 호스트 검증을 여기서 강제한다.
 */

export const FETCH_TIMEOUT_MS = 7000;
const SNIFF_BYTES = 32 * 1024;

const UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

/** SSRF 방지: 사설·루프백·링크로컬 대역으로 향하면 요청하지 않는다. */
export function isPrivateIp(ip: string): boolean {
  if (ip.includes(":")) {
    const v = ip.toLowerCase();
    if (v === "::1" || v === "::") return true;
    if (v.startsWith("::ffff:") || v.startsWith("fc") || v.startsWith("fd") || v.startsWith("fe80")) return true;
    return false;
  }
  const p = ip.split(".").map(Number);
  if (p.length !== 4 || p.some((n) => !Number.isInteger(n) || n < 0 || n > 255)) return true;
  const [a, b] = p;
  if (a === 0 || a === 10 || a === 127 || a >= 224) return true;
  if (a === 169 && b === 254) return true;
  if (a === 172 && b >= 16 && b <= 31) return true;
  if (a === 192 && b === 168) return true;
  if (a === 100 && b >= 64 && b <= 127) return true;
  return false;
}

/** 호스트가 공인 주소로 해석되는지 확인. 문제가 있으면 사유 문자열을 돌려준다. */
export async function checkHost(host: string): Promise<string | null> {
  if (!isValidHostname(host)) return "주소 형식 오류";
  let addrs: { address: string }[];
  try {
    addrs = await dns.lookup(host, { all: true });
  } catch {
    return "아직 없는 도메인";
  }
  if (!addrs.length || addrs.some((a) => isPrivateIp(a.address))) return "허용되지 않는 주소";
  return null;
}

/** 본문은 반환하지 않고 앞부분만 읽어 <title>만 뽑는다. */
async function sniffTitle(res: Response): Promise<string | undefined> {
  if (!res.body) return undefined;
  const reader = res.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (total < SNIFF_BYTES) {
      const { done, value } = await reader.read();
      if (done || !value) break;
      chunks.push(value);
      total += value.byteLength;
    }
  } catch {
    /* 중간에 끊겨도 읽은 만큼으로 시도 */
  } finally {
    void reader.cancel().catch(() => {});
  }
  const buf = new Uint8Array(total);
  let off = 0;
  for (const c of chunks) {
    const take = Math.min(c.byteLength, total - off);
    buf.set(c.subarray(0, take), off);
    off += take;
  }
  const html = new TextDecoder("utf-8", { fatal: false }).decode(buf);
  const m = html.match(/<title[^>]*>([\s\S]{0,200}?)<\/title>/i);
  const t = m?.[1].replace(/\s+/g, " ").trim();
  return t ? t.slice(0, 80) : undefined;
}

export type UrlProbe = {
  /** 네트워크 응답을 받았는지 (상태코드가 4xx여도 true) */
  reached: boolean;
  status?: number;
  finalUrl?: string;
  title?: string;
  /** 임베드 가능 여부 판정용 헤더 */
  xFrameOptions?: string;
  csp?: string;
  reason?: string;
};

export async function probeUrl(
  url: string,
  opts: { sniff?: boolean; headers?: boolean } = {},
): Promise<UrlProbe> {
  let host: string;
  try {
    const u = new URL(url);
    if (u.protocol !== "https:") return { reached: false, reason: "https만 허용" };
    host = u.hostname;
  } catch {
    return { reached: false, reason: "주소 형식 오류" };
  }

  const hostProblem = await checkHost(host);
  if (hostProblem) return { reached: false, reason: hostProblem };

  try {
    const res = await fetch(url, {
      redirect: "follow",
      cache: "no-store",
      signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
      headers: { "user-agent": UA, accept: "text/html,application/xhtml+xml" },
    });

    const out: UrlProbe = { reached: true, status: res.status, finalUrl: res.url };
    if (opts.headers) {
      out.xFrameOptions = res.headers.get("x-frame-options") ?? undefined;
      out.csp = res.headers.get("content-security-policy") ?? undefined;
    }
    if (opts.sniff) out.title = await sniffTitle(res);
    else void res.body?.cancel().catch(() => {});
    return out;
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    return { reached: false, reason: /timeout|abort/i.test(msg) ? "응답 없음" : "연결 실패" };
  }
}

export async function mapLimit<T, R>(items: T[], limit: number, fn: (t: T) => Promise<R>): Promise<R[]> {
  const out = new Array<R>(items.length);
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(limit, items.length) }, async () => {
      while (next < items.length) {
        const i = next++;
        out[i] = await fn(items[i]);
      }
    }),
  );
  return out;
}
