import { NextResponse } from "next/server";
import { SESSION_COOKIE, SESSION_MAX_AGE, accountNames, createSession, verifyCredentials } from "@/lib/auth";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

// 인스턴스 단위 최선노력 제한. 서버리스라 완벽하진 않지만 무차별 대입 속도는 크게 떨어뜨린다.
const attempts = new Map<string, { count: number; resetAt: number }>();
const WINDOW_MS = 10 * 60 * 1000;
const MAX_ATTEMPTS = 10;

function tooManyAttempts(key: string): boolean {
  const now = Date.now();
  const rec = attempts.get(key);
  if (!rec || now > rec.resetAt) {
    attempts.set(key, { count: 1, resetAt: now + WINDOW_MS });
    return false;
  }
  rec.count += 1;
  return rec.count > MAX_ATTEMPTS;
}

/** 로그인 화면에 띄울 계정 이름 목록 (PIN은 절대 내보내지 않는다). */
export async function GET() {
  return NextResponse.json({ accounts: accountNames() }, { headers: { "cache-control": "no-store" } });
}

export async function POST(req: Request) {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "잘못된 요청입니다." }, { status: 400 });
  }

  const name = String((body as { name?: unknown })?.name ?? "").trim();
  const pin = String((body as { pin?: unknown })?.pin ?? "").trim();
  if (!name || !pin) {
    return NextResponse.json({ error: "계정과 PIN을 입력해 주세요." }, { status: 400 });
  }

  const ip = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "unknown";
  if (tooManyAttempts(`${ip}:${name}`)) {
    return NextResponse.json({ error: "시도가 너무 많습니다. 10분 뒤에 다시 해주세요." }, { status: 429 });
  }

  const account = verifyCredentials(name, pin);
  if (!account) {
    return NextResponse.json({ error: "계정 또는 PIN이 맞지 않습니다." }, { status: 401 });
  }

  let token: string;
  try {
    token = await createSession(account.name);
  } catch (e) {
    return NextResponse.json(
      { error: e instanceof Error ? e.message : "세션 생성에 실패했습니다." },
      { status: 500 },
    );
  }

  const res = NextResponse.json({ name: account.name });
  res.cookies.set(SESSION_COOKIE, token, {
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: SESSION_MAX_AGE,
  });
  return res;
}
