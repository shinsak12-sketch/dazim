import { NextResponse } from "next/server";
import { getDomain } from "@/lib/db";
import { mapLimit, probeUrl } from "@/lib/probe";
import { currentProfile } from "@/lib/profile";
import { buildHost, clampNum } from "@/lib/site";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const MAX_COUNT = 20;
const CONCURRENCY = 6;

export type ProbeResult = {
  num: number;
  host: string;
  state: "alive" | "dead" | "error";
  status?: number;
  /** 리다이렉트로 도착한 최종 호스트. 다르면 그쪽이 진짜 현재 주소일 가능성이 높다. */
  finalHost?: string;
  title?: string;
  reason?: string;
};

/**
 * { from?, count? } 를 받아 저장된 도메인 패턴으로 호스트를 서버가 직접 조립해 확인한다.
 * 클라이언트가 임의 호스트명을 넣을 수 없게 해서 공격면을 좁혔다.
 */
export async function POST(req: Request) {
  const user = currentProfile();

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

    const results = await mapLimit(nums, CONCURRENCY, async (num): Promise<ProbeResult> => {
      const host = buildHost(domain, num);
      const r = await probeUrl(`https://${host}/`, { sniff: true });
      if (!r.reached) {
        return {
          num,
          host,
          state: r.reason === "허용되지 않는 주소" || r.reason === "주소 형식 오류" ? "error" : "dead",
          reason: r.reason,
        };
      }
      let finalHost: string | undefined;
      try {
        const h = new URL(r.finalUrl ?? "").hostname;
        if (h && h !== host) finalHost = h;
      } catch {
        /* noop */
      }
      return { num, host, state: "alive", status: r.status, finalHost, title: r.title };
    });

    return NextResponse.json({ results }, { headers: { "cache-control": "no-store" } });
  } catch (e) {
    return NextResponse.json({ error: e instanceof Error ? e.message : "확인 실패" }, { status: 500 });
  }
}
