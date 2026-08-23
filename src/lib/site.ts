/**
 * 도메인 패턴 파싱/조립.
 * tkor146.com → head:"" prefix:"tkor" num:146 pad:3 suffix:"com"
 * 클라이언트/서버 양쪽에서 쓰므로 node 전용 API를 쓰지 않는다.
 */

export type DomainPattern = {
  /** 번호 라벨 앞부분. 예: "www." (없으면 "") */
  head: string;
  /** 번호 라벨의 문자 부분. 예: "tkor" */
  prefix: string;
  /** 번호 라벨 뒷부분. 예: "com", "co.kr" */
  suffix: string;
  /** 번호 자릿수(앞 0 채움용). 예: 3 */
  pad: number;
  /** 현재 번호. 예: 146 */
  num: number;
};

export const MIN_NUM = 0;
export const MAX_NUM = 99999;

export function buildHost(d: DomainPattern, num: number = d.num): string {
  const n = String(clampNum(num)).padStart(d.pad, "0");
  return `${d.head}${d.prefix}${n}.${d.suffix}`;
}

export function buildUrl(d: DomainPattern, path: string, num: number = d.num): string {
  const p = path.startsWith("/") ? path : `/${path}`;
  return `https://${buildHost(d, num)}${p}`;
}

export function clampNum(n: number): number {
  if (!Number.isFinite(n)) return MIN_NUM;
  return Math.min(MAX_NUM, Math.max(MIN_NUM, Math.trunc(n)));
}

/** 호스트명에서 "글자+숫자" 라벨을 찾아 패턴으로 분해한다. 못 찾으면 null. */
export function parseHost(hostname: string): DomainPattern | null {
  const host = hostname.trim().toLowerCase();
  if (!host) return null;
  const labels = host.split(".");
  // 마지막 라벨(TLD)은 번호 자리로 보지 않는다.
  for (let i = 0; i < labels.length - 1; i++) {
    if (labels[i] === "www") continue;
    const m = labels[i].match(/^(.*?)(\d+)$/);
    if (!m) continue;
    return {
      head: i > 0 ? `${labels.slice(0, i).join(".")}.` : "",
      prefix: m[1],
      suffix: labels.slice(i + 1).join("."),
      pad: m[2].length,
      num: clampNum(Number(m[2])),
    };
  }
  return null;
}

export type ParsedInput = {
  /** 주소에서 도메인을 읽어낸 경우의 패턴. 경로만 입력했으면 null */
  pattern: DomainPattern | null;
  /** 쿼리·해시까지 포함한 경로. 항상 "/"로 시작 */
  path: string;
};

/** 사용자가 붙여넣은 문자열(전체 주소 또는 경로만)을 파싱한다. */
export function parseInput(input: string): ParsedInput | null {
  const s = input.trim();
  if (!s) return null;

  // 경로만 붙여넣은 경우
  if (s.startsWith("/")) return { pattern: null, path: s };

  const withScheme = /^https?:\/\//i.test(s) ? s : `https://${s}`;
  let u: URL;
  try {
    u = new URL(withScheme);
  } catch {
    return null;
  }
  return {
    pattern: parseHost(u.hostname),
    path: `${u.pathname}${u.search}${u.hash}` || "/",
  };
}

/** 번호를 뺀 도메인 모양이 같은지 (같으면 번호만 다른 미러) */
export function sameShape(a: DomainPattern, b: DomainPattern): boolean {
  return a.head === b.head && a.prefix === b.prefix && a.suffix === b.suffix;
}

/** 경로에서 만화 제목을 추측한다. /나_혼자_..._266화.html → "나 혼자 ..." */
export function guessTitle(path: string): string {
  let s = path;
  try {
    s = decodeURIComponent(path);
  } catch {
    /* 잘못된 인코딩이면 원본 그대로 */
  }
  s = s.split("?")[0].split("#")[0];
  s = s.slice(s.lastIndexOf("/") + 1);
  s = s.replace(/\.(html?|php|aspx?|jsp)$/i, "");
  s = s.replace(/[_+]+/g, " ").trim();
  s = s.replace(/\s*\d+\s*화\s*$/, "").trim(); // 끝의 "266화" 제거
  return s || "제목 없음";
}

/** 화면 표시용으로 퍼센트 인코딩을 푼다. */
export function prettyPath(path: string): string {
  try {
    return decodeURIComponent(path);
  } catch {
    return path;
  }
}

const HOSTNAME_RE =
  /^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?)+$/i;

export function isValidHostname(host: string): boolean {
  return host.length > 0 && host.length <= 100 && HOSTNAME_RE.test(host);
}

/** 사용자가 보낸 도메인 패턴이 안전하고 조립 가능한지 검증한다. */
export function validatePattern(p: DomainPattern): string | null {
  if (p.pad < 0 || p.pad > 5) return "번호 자릿수가 올바르지 않습니다.";
  if (p.num < MIN_NUM || p.num > MAX_NUM) return "번호 범위를 벗어났습니다.";
  if (!/^[a-z0-9-]{0,60}$/i.test(p.prefix)) return "도메인 앞부분이 올바르지 않습니다.";
  if (p.head !== "" && !/^[a-z0-9.-]{1,60}\.$/i.test(p.head)) return "서브도메인이 올바르지 않습니다.";
  if (!/^[a-z]{2,}(\.[a-z]{2,})*$/i.test(p.suffix)) return "도메인 뒷부분(.com 등)이 올바르지 않습니다.";
  if (!isValidHostname(buildHost(p))) return "조합된 주소가 올바르지 않습니다.";
  return null;
}

/** 경로 검증: 절대경로만 허용하고 스킴/호스트가 섞여 들어오는 걸 막는다. */
export function validatePath(path: string): string | null {
  if (!path.startsWith("/")) return "경로는 /로 시작해야 합니다.";
  if (path.startsWith("//")) return "경로가 올바르지 않습니다.";
  if (path.length > 1000) return "경로가 너무 깁니다.";
  if (/[\s<>"']/.test(path)) return "경로에 쓸 수 없는 문자가 있습니다.";
  return null;
}
