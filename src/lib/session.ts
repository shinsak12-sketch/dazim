import { cookies } from "next/headers";
import { SESSION_COOKIE, readSession } from "./auth";

/** 라우트 핸들러에서 로그인한 계정 이름을 읽는다. 없으면 null. */
export async function currentUser(): Promise<string | null> {
  try {
    return await readSession(cookies().get(SESSION_COOKIE)?.value);
  } catch {
    return null;
  }
}
