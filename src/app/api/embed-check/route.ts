import { NextResponse } from "next/server";
import { getDomain, listComics } from "@/lib/db";
import { probeUrl } from "@/lib/probe";
import { currentUser } from "@/lib/session";
import { buildUrl } from "@/lib/site";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export type EmbedVerdict = {
  /** 앱 안(iframe)에 띄울 수 있는지 */
  embeddable: boolean;
  /** 판정 근거를 사람이 읽을 수 있게 */
  reason: string;
  xFrameOptions?: string;
  frameAncestors?: string;
  status?: number;
  reached: boolean;
};

/** CSP 문자열에서 frame-ancestors 지시문만 뽑는다. */
function frameAncestorsOf(csp: string | undefined): string | undefined {
  if (!csp) return undefined;
  for (const part of csp.split(";")) {
    const t = part.trim();
    if (/^frame-ancestors\b/i.test(t)) return t;
  }
  return undefined;
}

/**
 * 대상 사이트가 iframe 삽입을 허용하는지 응답 헤더로 판정한다.
 * 브라우저에서는 차단돼도 onload가 불려서 구분이 안 되므로 서버에서 헤더를 직접 본다.
 */
export async function POST(req: Request) {
  const user = await currentUser();
  if (!user) return NextResponse.json({ error: "로그인이 필요합니다." }, { status: 401 });

  let body: Record<string, unknown> = {};
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    /* 본문 없으면 도메인 루트를 본다 */
  }

  try {
    const domain = await getDomain();
    let path = "/";
    const id = String(body.id ?? "").trim();
    if (id) {
      const comic = (await listComics(user)).find((c) => c.id === id);
      if (comic) path = comic.path;
    }

    const r = await probeUrl(buildUrl(domain, path), { headers: true });
    if (!r.reached) {
      const verdict: EmbedVerdict = {
        embeddable: false,
        reason: `사이트에 접속하지 못했습니다 (${r.reason ?? "원인 불명"}).`,
        reached: false,
      };
      return NextResponse.json(verdict, { headers: { "cache-control": "no-store" } });
    }

    const xfo = r.xFrameOptions?.trim();
    const fa = frameAncestorsOf(r.csp);

    let embeddable = true;
    let reason = "차단 헤더가 없습니다. 앱 안에서 열릴 가능성이 높습니다.";

    if (xfo && /deny/i.test(xfo)) {
      embeddable = false;
      reason = "사이트가 X-Frame-Options: DENY 로 삽입을 완전히 막고 있습니다.";
    } else if (xfo && /sameorigin/i.test(xfo)) {
      embeddable = false;
      reason = "사이트가 X-Frame-Options: SAMEORIGIN 이라 다른 도메인에서는 못 띄웁니다.";
    } else if (fa && /'none'/i.test(fa)) {
      embeddable = false;
      reason = "사이트 CSP가 frame-ancestors 'none' 으로 삽입을 막고 있습니다.";
    } else if (fa && /'self'/i.test(fa) && !/\*/.test(fa)) {
      embeddable = false;
      reason = "사이트 CSP가 frame-ancestors 'self' 라 다른 도메인에서는 못 띄웁니다.";
    } else if (fa) {
      embeddable = false;
      reason = `사이트 CSP가 삽입 가능한 곳을 제한하고 있습니다: ${fa}`;
    }

    const verdict: EmbedVerdict = {
      embeddable,
      reason,
      xFrameOptions: xfo,
      frameAncestors: fa,
      status: r.status,
      reached: true,
    };
    return NextResponse.json(verdict, { headers: { "cache-control": "no-store" } });
  } catch (e) {
    return NextResponse.json({ error: e instanceof Error ? e.message : "확인 실패" }, { status: 500 });
  }
}
