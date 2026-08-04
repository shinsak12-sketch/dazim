import { NextResponse } from "next/server";
import { sql, ensureSchema } from "@/lib/db";

export const dynamic = "force-dynamic";

// GET /api/health — 배포 진단용(민감정보 미노출).
// 브라우저에서 /api/health 로 접속해 설정 상태를 확인할 수 있다.
export async function GET() {
  const hasDatabaseUrl = !!process.env.DATABASE_URL;
  const hasAdminKey = !!process.env.ADMIN_KEY;

  let db: "ok" | "error" | "no_database_url" = "no_database_url";
  let count: number | null = null;
  let error: string | null = null;

  if (hasDatabaseUrl) {
    try {
      await ensureSchema();
      const rows = (await sql`select count(*)::int as count from pledges`) as { count: number }[];
      count = rows[0]?.count ?? 0;
      db = "ok";
    } catch (e) {
      db = "error";
      error = e instanceof Error ? e.message : String(e);
    }
  }

  const hint =
    !hasDatabaseUrl
      ? "Vercel 프로젝트에 DATABASE_URL 환경변수를 설정하세요."
      : db === "error"
        ? "DATABASE_URL 값이 올바른지(끝에 ?sslmode=require 포함), Neon DB가 활성 상태인지 확인하세요."
        : !hasAdminKey
          ? "정상. 다만 ADMIN_KEY가 없어 /admin 진입이 불가합니다."
          : "정상.";

  return NextResponse.json(
    { ok: db === "ok", db, hasDatabaseUrl, hasAdminKey, count, error, hint },
    { status: db === "ok" ? 200 : 500 }
  );
}
