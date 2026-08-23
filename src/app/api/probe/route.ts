import { NextResponse } from "next/server";
import dns from "node:dns/promises";
import { getDomain } from "@/lib/db";
import { currentUser } from "@/lib/session";
import { buildHost, clampNum, isValidHostname } from "@/lib/site";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const MAX_COUNT = 20;
const FETCH_TIMEOUT_MS = 7000;
const SNIFF_BYTES = 32 * 1024;
const CONCURRENCY = 6;

export type ProbeResult = {
  num: number;
  host: string;
  /** alive: 응답함 / dead: 도메인 없음·연결 실패 / error: 그 외 */
  state: "alive" | "dead" | "error";
  status?: number;
  /** 리다이렉트로 도착한 최종 호스트. 다르면 그쪽이 진짜 현재 주소일 가능성이 높다. */
  finalHost?: string;
  title?: string;
  reason?: string;
};

/** SSRF 방지: 사설·루프백·링크로컬로 향하는 호스트는 요청하지 않는다. */
function isPrivateIp(ip: string): boolean {
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

/** 본문은 돌려주지 않고 앞부분만 읽어 <title>만 뽑는다. */
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

async function probeOne(num: number, host: string): Promise<ProbeResult> {
  if (!isValidHostname(host)) return { num, host, state: "error", reason: "주소 형식 오류" };

  let addrs: { address: string }[];
  try {
    addrs = await dns.lookup(host, { all: true });
  } catch {
    return { num, host, state: "dead", reason: "아직 없는 도메인" };
  }
  if (!addrs.length || addrs.some((a) => isPrivateIp(a.address))) {
    return { num, host, state: "error", reason: "허용되지 않는 주소" };
  }

  try {
    const res = await fetch(`https://${host}/`, {
      redirect: "follow",
      cache: "no-store",
      signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
      headers: {
        // 기본 UA를 막는 사이트가 있어 일반 브라우저처럼 보이게 한다.
        "user-agent":
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        accept: "text/html,application/xhtml+xml",
      },
    });
    let finalHost: string | undefined;
    try {
      const h = new URL(res.url).hostname;
      if (h && h !== host) finalHost = h;
    } catch {
      /* noop */
    }
    return { num, host, state: "alive", status: res.status, finalHost, title: await sniffTitle(res) };
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    return {
      num,
      host,
      state: "dead",
      reason: /timeout|abort/i.test(msg) ? "응답 없음" : "연결 실패",
    };
  }
}

async function mapLimit<T, R>(items: T[], limit: number, fn: (t: T) => Promise<R>): Promise<R[]> {
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

/**
 * { from?, count? } 를 받아 저장된 도메인 패턴으로 호스트를 서버가 직접 조립해 확인한다.
 * 클라이언트가 임의 호스트명을 넣을 수 없게 해서 공격면을 좁혔다.
 */
export async function POST(req: Request) {
  const user = await currentUser();
  if (!user) return NextResponse.json({ error: "로그인이 필요합니다." }, { status: 401 });

  let body: Record<string, unknown> = {};
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    /* 본문 없이 호출하면 기본값 사용 */
  }

  try {
    const domain = await getDomain();
    const from = body.from !== undefined ? clampNum(Number(body.from)) : domain.num;
    const count = Math.min(MAX_COUNT, Math.max(1, Number(body.count ?? 12) || 12));

    const nums = Array.from({ length: count }, (_, i) => clampNum(from + i));
    const results = await mapLimit(nums, CONCURRENCY, (n) => probeOne(n, buildHost(domain, n)));

    return NextResponse.json({ results }, { headers: { "cache-control": "no-store" } });
  } catch (e) {
    return NextResponse.json({ error: e instanceof Error ? e.message : "확인 실패" }, { status: 500 });
  }
}
