import { NextRequest, NextResponse } from "next/server";
import { isAdmin } from "@/lib/auth";

export const dynamic = "force-dynamic";

// POST /api/admin/verify — 관리자 키 확인. 헤더 x-admin-key 로 전달.
export async function POST(req: NextRequest) {
  if (!process.env.ADMIN_KEY) {
    return NextResponse.json(
      { ok: false, error: "서버에 ADMIN_KEY가 설정되지 않았습니다." },
      { status: 500 }
    );
  }
  if (isAdmin(req)) {
    return NextResponse.json({ ok: true });
  }
  return NextResponse.json({ ok: false, error: "키가 올바르지 않습니다." }, { status: 401 });
}
