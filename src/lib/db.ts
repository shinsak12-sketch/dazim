import { neon, NeonQueryFunction } from "@neondatabase/serverless";

export type PledgeRow = {
  id: string;
  name: string;
  team: string;
  content: string;
  revealed: boolean;
  created_at: string;
  updated_at: string;
};

// neon 클라이언트를 지연 초기화한다.
// 빌드 타임(page data 수집)에는 DATABASE_URL이 없을 수 있으므로,
// 실제 쿼리가 실행되는 요청 시점에만 연결 문자열을 검증/사용한다.
let _client: NeonQueryFunction<false, false> | null = null;

function getClient(): NeonQueryFunction<false, false> {
  if (!_client) {
    const url = process.env.DATABASE_URL;
    if (!url) {
      throw new Error("DATABASE_URL 환경변수가 설정되지 않았습니다.");
    }
    _client = neon(url);
  }
  return _client;
}

// 태그드 템플릿 형태로 사용: sql`select ...`
export const sql: NeonQueryFunction<false, false> = ((
  strings: TemplateStringsArray,
  ...values: unknown[]
) => {
  return getClient()(strings, ...values);
}) as NeonQueryFunction<false, false>;
