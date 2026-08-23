import { NextResponse } from "next/server";
import { PROFILE_COOKIE, currentProfile, listProfiles, normalizeProfile } from "@/lib/profile";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const YEAR = 60 * 60 * 24 * 365;

export async function GET() {
  return NextResponse.json(
    { current: currentProfile(), profiles: listProfiles() },
    { headers: { "cache-control": "no-store" } },
  );
}

/** 비밀번호 없는 단순 전환. 설치형 앱 전제라 인증을 두지 않는다. */
export async function POST(req: Request) {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return NextResponse.json({ error: "잘못된 요청입니다." }, { status: 400 });
  }

  const name = normalizeProfile(String(body.name ?? ""));
  const res = NextResponse.json({ current: name });
  res.cookies.set(PROFILE_COOKIE, name, {
    httpOnly: false,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: YEAR,
  });
  return res;
}
