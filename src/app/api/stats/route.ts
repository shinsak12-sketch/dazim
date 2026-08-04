import { NextResponse } from "next/server";
import { sql, ensureSchema } from "@/lib/db";

export const dynamic = "force-dynamic";

// GET /api/stats — 제출 인원수(공개). QR 안내 화면용.
export async function GET() {
  try {
    await ensureSchema();
    const rows = (await sql`select count(*)::int as count from pledges`) as { count: number }[];
    return NextResponse.json({ count: rows[0]?.count ?? 0 });
  } catch (e) {
    console.error("[GET /api/stats]", e);
    return NextResponse.json({ count: 0 }, { status: 500 });
  }
}
