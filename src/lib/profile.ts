import { cookies } from "next/headers";

/**
 * 설치형 앱이라 로그인을 두지 않는다.
 * "누구 목록인가"만 구분하면 되므로 서명 없는 단순 쿠키를 쓴다.
 */

export const PROFILE_COOKIE = "toon_profile";
export const DEFAULT_PROFILES = ["남편", "아내"];

/** PROFILES="남편,아내" 로 바꿀 수 있다. 미설정이면 기본값. */
export function listProfiles(): string[] {
  const raw = (process.env.PROFILES ?? "").trim();
  if (!raw) return DEFAULT_PROFILES;
  const names = raw
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean)
    .slice(0, 10);
  return names.length ? names : DEFAULT_PROFILES;
}

export function normalizeProfile(name: string | undefined | null): string {
  const profiles = listProfiles();
  const found = profiles.find((p) => p === (name ?? "").trim());
  return found ?? profiles[0];
}

/** 라우트 핸들러에서 현재 프로필을 읽는다. 값이 이상하면 첫 번째 프로필로 떨어뜨린다. */
export function currentProfile(): string {
  try {
    return normalizeProfile(cookies().get(PROFILE_COOKIE)?.value);
  } catch {
    return listProfiles()[0];
  }
}
