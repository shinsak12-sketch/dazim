import { NextResponse } from "next/server";
import { ensureSchema } from "@/lib/db";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** 배포 후 환경변수·DB 연결이 제대로 잡혔는지 한 번에 확인하는 용도. */
export async function GET() {
  const env = {
    DATABASE_URL: Boolean(process.env.DATABASE_URL),
    AUTH_SECRET: Boolean(process.env.AUTH_SECRET && process.env.AUTH_SECRET.length >= 16),
    ACCOUNTS: Boolean(process.env.ACCOUNTS),
  };
  let db = "skipped";
  if (env.DATABASE_URL) {
    try {
      await ensureSchema();
      db = "ok";
    } catch (e) {
      db = e instanceof Error ? e.message : "error";
    }
  }
  const ok = env.DATABASE_URL && env.AUTH_SECRET && env.ACCOUNTS && db === "ok";
  return NextResponse.json({ ok, env, db }, { status: ok ? 200 : 503, headers: { "cache-control": "no-store" } });
}
