import { NextResponse } from "next/server";
import { ensureSchema } from "@/lib/db";
import { listProfiles } from "@/lib/profile";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** 배포 후 DB 연결이 제대로 잡혔는지 한 번에 확인하는 용도. */
export async function GET() {
  const hasDb = Boolean(process.env.DATABASE_URL);
  let db = "skipped";
  if (hasDb) {
    try {
      await ensureSchema();
      db = "ok";
    } catch (e) {
      db = e instanceof Error ? e.message : "error";
    }
  }
  const ok = hasDb && db === "ok";
  return NextResponse.json(
    { ok, env: { DATABASE_URL: hasDb }, profiles: listProfiles(), db },
    { status: ok ? 200 : 503, headers: { "cache-control": "no-store" } },
  );
}
