import { NextResponse } from "next/server";
import { getDomain, listComics } from "@/lib/db";
import { mapLimit, probeUrl } from "@/lib/probe";
import { currentProfile } from "@/lib/profile";
import { buildEpisodePath, buildUrl, parseEpisode } from "@/lib/site";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const MAX_COUNT = 15;
const CONCURRENCY = 5;

export type EpisodeResult = { ep: number; path: string; exists: boolean; status?: number; reason?: string };

/**
 * 특정 만화의 다음 회차들이 실제로 올라왔는지 확인한다.
 * 경로는 서버가 저장된 값에서 조립하므로 클라이언트가 임의 주소를 넣을 수 없다.
 */
export async function POST(req: Request) {
  const user = currentProfile();

  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return NextResponse.json({ error: "잘못된 요청입니다." }, { status: 400 });
  }

  const id = String(body.id ?? "").trim();
  if (!id) return NextResponse.json({ error: "id가 필요합니다." }, { status: 400 });

  try {
    const comic = (await listComics(user)).find((c) => c.id === id);
    if (!comic) return NextResponse.json({ error: "만화를 찾을 수 없습니다." }, { status: 404 });

    const ref = parseEpisode(comic.path);
    if (!ref) {
      return NextResponse.json({ error: "이 주소에서는 회차 번호를 찾지 못했습니다." }, { status: 400 });
    }

    const domain = await getDomain();
    const count = Math.min(MAX_COUNT, Math.max(1, Number(body.count ?? 8) || 8));
    // 현재 화 다음부터 확인한다.
    const eps = Array.from({ length: count }, (_, i) => ref.ep + 1 + i);

    const results = await mapLimit(eps, CONCURRENCY, async (ep): Promise<EpisodeResult> => {
      const path = buildEpisodePath(ref, ep);
      const r = await probeUrl(buildUrl(domain, path));
      if (!r.reached) return { ep, path, exists: false, reason: r.reason };
      // 없는 화는 보통 404. 200이면 올라온 것으로 본다.
      return { ep, path, exists: r.status === 200, status: r.status };
    });

    // 현재 화 다음부터 끊기지 않고 이어지는 마지막 회차 = 지금 볼 수 있는 최신화
    let latest = ref.ep;
    for (const r of results) {
      if (r.ep === latest + 1 && r.exists) latest = r.ep;
      else break;
    }

    return NextResponse.json(
      { current: ref.ep, latest, results },
      { headers: { "cache-control": "no-store" } },
    );
  } catch (e) {
    return NextResponse.json({ error: e instanceof Error ? e.message : "확인 실패" }, { status: 500 });
  }
}
