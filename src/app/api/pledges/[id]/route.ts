import { NextRequest, NextResponse } from "next/server";
import { sql, PledgeRow } from "@/lib/db";
import { validatePledge } from "@/lib/validation";
import { isAdmin } from "@/lib/auth";

export const dynamic = "force-dynamic";

// GET /api/pledges/[id] — 본인 제출 내용 조회(재접속 시 완료 화면 복원용).
export async function GET(_req: NextRequest, { params }: { params: { id: string } }) {
  try {
    const rows = (await sql`
      select id, name, team, content, revealed, created_at, updated_at
      from pledges
      where id = ${params.id}
    `) as PledgeRow[];
    if (rows.length === 0) {
      return NextResponse.json({ error: "제출 내역을 찾을 수 없습니다." }, { status: 404 });
    }
    return NextResponse.json({ pledge: rows[0] });
  } catch (e) {
    console.error("[GET /api/pledges/[id]]", e);
    return NextResponse.json({ error: "조회에 실패했습니다." }, { status: 500 });
  }
}

// PATCH /api/pledges/[id]
//  - 관리자 키가 있고 body.revealed 가 있으면: 공개 상태 변경(강사용)
//  - 그 외: 직원 본인 내용 수정(name/team/content)
export async function PATCH(req: NextRequest, { params }: { params: { id: string } }) {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "잘못된 요청입니다." }, { status: 400 });
  }

  const b = (body ?? {}) as Record<string, unknown>;

  // 관리자: 공개 상태 토글
  if (typeof b.revealed === "boolean") {
    if (!isAdmin(req)) {
      return NextResponse.json({ error: "인증이 필요합니다." }, { status: 401 });
    }
    try {
      const rows = (await sql`
        update pledges
        set revealed = ${b.revealed}, updated_at = now()
        where id = ${params.id}
        returning id, name, team, content, revealed, created_at, updated_at
      `) as PledgeRow[];
      if (rows.length === 0) {
        return NextResponse.json({ error: "대상을 찾을 수 없습니다." }, { status: 404 });
      }
      return NextResponse.json({ pledge: rows[0] });
    } catch (e) {
      console.error("[PATCH reveal]", e);
      return NextResponse.json({ error: "상태 변경에 실패했습니다." }, { status: 500 });
    }
  }

  // 직원: 내용 수정
  const result = validatePledge(body);
  if (!result.ok) {
    return NextResponse.json({ error: result.error }, { status: 400 });
  }
  const { name, team, content } = result.value;
  try {
    const rows = (await sql`
      update pledges
      set name = ${name}, team = ${team}, content = ${content}, updated_at = now()
      where id = ${params.id}
      returning id, name, team, content, revealed, created_at, updated_at
    `) as PledgeRow[];
    if (rows.length === 0) {
      return NextResponse.json({ error: "수정할 제출 내역을 찾을 수 없습니다." }, { status: 404 });
    }
    return NextResponse.json({ pledge: rows[0] });
  } catch (e) {
    console.error("[PATCH edit]", e);
    return NextResponse.json({ error: "수정에 실패했습니다." }, { status: 500 });
  }
}
