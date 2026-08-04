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

// 스키마 지연 자동 생성.
// schema.sql을 수동 실행하지 않아도 첫 DB 접근 시 테이블/인덱스를 만든다.
// create ... if not exists 라 여러 번 호출·기존 DB에도 안전(멱등).
let schemaReady: Promise<void> | null = null;

export function ensureSchema(): Promise<void> {
  if (!schemaReady) {
    const client = getClient();
    schemaReady = (async () => {
      await client`
        create table if not exists pledges (
          id text primary key,
          name text not null,
          team text not null default '',
          content text not null,
          revealed boolean not null default false,
          created_at timestamptz not null default now(),
          updated_at timestamptz not null default now()
        )
      `;
      await client`create index if not exists idx_pledges_created on pledges(created_at)`;
    })().catch((e) => {
      // 실패 시 다음 요청에서 재시도할 수 있도록 캐시 해제
      schemaReady = null;
      throw e;
    });
  }
  return schemaReady;
}
