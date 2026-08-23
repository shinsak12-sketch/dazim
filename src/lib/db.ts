import { neon, NeonQueryFunction } from "@neondatabase/serverless";
import { clampNum, type DomainPattern } from "./site";

export type DomainRow = DomainPattern & { updated_at: string; updated_by: string };

export type ComicRow = {
  id: string;
  owner: string;
  title: string;
  path: string;
  sort: number;
  created_at: string;
  updated_at: string;
};

export const DOMAIN_ID = "default";

// 빌드 타임에는 DATABASE_URL이 없을 수 있으므로 실제 쿼리 시점에만 검증한다.
let _client: NeonQueryFunction<false, false> | null = null;

function getClient(): NeonQueryFunction<false, false> {
  if (!_client) {
    const url = process.env.DATABASE_URL;
    if (!url) throw new Error("DATABASE_URL 환경변수가 설정되지 않았습니다.");
    _client = neon(url);
  }
  return _client;
}

export const sql: NeonQueryFunction<false, false> = ((
  strings: TemplateStringsArray,
  ...values: unknown[]
) => getClient()(strings, ...values)) as NeonQueryFunction<false, false>;

// 첫 접근 시 테이블을 만든다. create ... if not exists 라 멱등하다.
let schemaReady: Promise<void> | null = null;

export function ensureSchema(): Promise<void> {
  if (!schemaReady) {
    const client = getClient();
    schemaReady = (async () => {
      await client`
        create table if not exists domain_state (
          id         text primary key,
          head       text not null default '',
          prefix     text not null,
          suffix     text not null,
          pad        int  not null default 0,
          num        int  not null,
          updated_at timestamptz not null default now(),
          updated_by text not null default ''
        )
      `;
      await client`
        create table if not exists comics (
          id         text primary key,
          owner      text not null,
          title      text not null,
          path       text not null,
          sort       int  not null default 0,
          created_at timestamptz not null default now(),
          updated_at timestamptz not null default now()
        )
      `;
      await client`create index if not exists idx_comics_owner_sort on comics(owner, sort, created_at)`;
      // 최초 1회 기본값 심기. 이미 있으면 건드리지 않는다.
      await client`
        insert into domain_state (id, head, prefix, suffix, pad, num)
        values (${DOMAIN_ID}, '', 'tkor', 'com', 3, 146)
        on conflict (id) do nothing
      `;
    })().catch((e) => {
      schemaReady = null; // 다음 요청에서 재시도
      throw e;
    });
  }
  return schemaReady;
}

export async function getDomain(): Promise<DomainRow> {
  await ensureSchema();
  const rows = (await sql`
    select head, prefix, suffix, pad, num, updated_at, updated_by
    from domain_state where id = ${DOMAIN_ID}
  `) as Record<string, unknown>[];
  const r = rows[0];
  return {
    head: String(r.head ?? ""),
    prefix: String(r.prefix ?? "tkor"),
    suffix: String(r.suffix ?? "com"),
    pad: Number(r.pad ?? 0),
    num: clampNum(Number(r.num ?? 0)),
    updated_at: String(r.updated_at ?? ""),
    updated_by: String(r.updated_by ?? ""),
  };
}

export async function saveDomain(d: DomainPattern, by: string): Promise<DomainRow> {
  await ensureSchema();
  await sql`
    update domain_state
       set head = ${d.head}, prefix = ${d.prefix}, suffix = ${d.suffix},
           pad = ${d.pad}, num = ${clampNum(d.num)},
           updated_at = now(), updated_by = ${by}
     where id = ${DOMAIN_ID}
  `;
  return getDomain();
}

export async function listComics(owner: string): Promise<ComicRow[]> {
  await ensureSchema();
  return (await sql`
    select id, owner, title, path, sort, created_at, updated_at
      from comics where owner = ${owner}
     order by sort asc, created_at asc
  `) as unknown as ComicRow[];
}

export async function insertComic(
  owner: string,
  title: string,
  path: string,
): Promise<ComicRow> {
  await ensureSchema();
  const id = crypto.randomUUID();
  const rows = (await sql`
    insert into comics (id, owner, title, path, sort)
    values (
      ${id}, ${owner}, ${title}, ${path},
      coalesce((select max(sort) + 1 from comics where owner = ${owner}), 0)
    )
    returning id, owner, title, path, sort, created_at, updated_at
  `) as unknown as ComicRow[];
  return rows[0];
}

export async function updateComic(
  owner: string,
  id: string,
  patch: { title?: string; path?: string },
): Promise<ComicRow | null> {
  await ensureSchema();
  const rows = (await sql`
    update comics
       set title = coalesce(${patch.title ?? null}, title),
           path  = coalesce(${patch.path ?? null}, path),
           updated_at = now()
     where id = ${id} and owner = ${owner}
    returning id, owner, title, path, sort, created_at, updated_at
  `) as unknown as ComicRow[];
  return rows[0] ?? null;
}

export async function deleteComic(owner: string, id: string): Promise<boolean> {
  await ensureSchema();
  const rows = (await sql`
    delete from comics where id = ${id} and owner = ${owner} returning id
  `) as unknown as { id: string }[];
  return rows.length > 0;
}

/** 전달받은 id 순서대로 sort를 0,1,2...로 다시 매긴다. 남의 행은 건드리지 않는다. */
export async function reorderComics(owner: string, ids: string[]): Promise<void> {
  await ensureSchema();
  if (ids.length === 0) return;
  // 배열 파라미터 인코딩에 기대지 않도록 jsonb로 넘긴다.
  await sql`
    update comics as c
       set sort = v.idx, updated_at = now()
      from (
        select value #>> '{}' as id, (ordinality - 1)::int as idx
          from jsonb_array_elements(${JSON.stringify(ids)}::jsonb) with ordinality
      ) as v
     where c.id = v.id and c.owner = ${owner}
  `;
}
