import { NextResponse } from "next/server";
import { getDomain, saveDomain } from "@/lib/db";
import { currentUser } from "@/lib/session";
import { buildHost, clampNum, validatePattern, type DomainPattern } from "@/lib/site";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const noStore = { "cache-control": "no-store" };

export async function GET() {
  const user = await currentUser();
  if (!user) return NextResponse.json({ error: "로그인이 필요합니다." }, { status: 401 });

  try {
    const d = await getDomain();
    return NextResponse.json({ domain: { ...d, host: buildHost(d) } }, { headers: noStore });
  } catch (e) {
    return NextResponse.json({ error: e instanceof Error ? e.message : "조회 실패" }, { status: 500 });
  }
}

/**
 * 번호만 바꾸려면 { num } 또는 { delta: 1 },
 * 도메인 모양까지 바꾸려면 { head, prefix, suffix, pad, num } 을 보낸다.
 */
export async function PATCH(req: Request) {
  const user = await currentUser();
  if (!user) return NextResponse.json({ error: "로그인이 필요합니다." }, { status: 401 });

  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return NextResponse.json({ error: "잘못된 요청입니다." }, { status: 400 });
  }

  try {
    const cur = await getDomain();
    const hasShape = ["head", "prefix", "suffix", "pad"].some((k) => body[k] !== undefined);

    const next: DomainPattern = hasShape
      ? {
          head: String(body.head ?? cur.head),
          prefix: String(body.prefix ?? cur.prefix),
          suffix: String(body.suffix ?? cur.suffix),
          pad: Number(body.pad ?? cur.pad),
          num: clampNum(Number(body.num ?? cur.num)),
        }
      : {
          ...cur,
          num:
            body.delta !== undefined
              ? clampNum(cur.num + Number(body.delta))
              : clampNum(Number(body.num ?? cur.num)),
        };

    if (!Number.isFinite(next.num) || !Number.isFinite(next.pad)) {
      return NextResponse.json({ error: "숫자 값이 올바르지 않습니다." }, { status: 400 });
    }

    const invalid = validatePattern(next);
    if (invalid) return NextResponse.json({ error: invalid }, { status: 400 });

    const saved = await saveDomain(next, user);
    return NextResponse.json({ domain: { ...saved, host: buildHost(saved) } }, { headers: noStore });
  } catch (e) {
    return NextResponse.json({ error: e instanceof Error ? e.message : "저장 실패" }, { status: 500 });
  }
}
