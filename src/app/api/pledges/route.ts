import { NextRequest, NextResponse } from "next/server";
import { randomUUID } from "crypto";
import { sql, ensureSchema, PledgeRow } from "@/lib/db";
import { validatePledge } from "@/lib/validation";
import { isAdmin } from "@/lib/auth";

export const dynamic = "force-dynamic";

// GET /api/pledges — 강사용 전체 목록(내용 포함). 관리자 키 필요.
export async function GET(req: NextRequest) {
  if (!isAdmin(req)) {
    return NextResponse.json({ error: "인증이 필요합니다." }, { status: 401 });
  }
  try {
    await ensureSchema();
    const rows = (await sql`
      select id, name, team, content, revealed, created_at, updated_at
      from pledges
      order by created_at asc
    `) as PledgeRow[];
    return NextResponse.json({ pledges: rows });
  } catch (e) {
    console.error("[GET /api/pledges]", e);
    return NextResponse.json({ error: "목록을 불러오지 못했습니다." }, { status: 500 });
  }
}

// POST /api/pledges — 직원 다짐 제출(신규). 공개 엔드포인트.
export async function POST(req: NextRequest) {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "잘못된 요청입니다." }, { status: 400 });
  }

  const result = validatePledge(body);
  if (!result.ok) {
    return NextResponse.json({ error: result.error }, { status: 400 });
  }

  const { name, team, content } = result.value;
  const id = randomUUID();

  try {
    await ensureSchema();
    const rows = (await sql`
      insert into pledges (id, name, team, content)
      values (${id}, ${name}, ${team}, ${content})
      returning id, name, team, content, revealed, created_at, updated_at
    `) as PledgeRow[];
    return NextResponse.json({ pledge: rows[0] }, { status: 201 });
  } catch (e) {
    console.error("[POST /api/pledges]", e);
    return NextResponse.json({ error: "제출에 실패했습니다. 잠시 후 다시 시도해 주세요." }, { status: 500 });
  }
}
