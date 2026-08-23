import { NextResponse } from "next/server";
import { deleteComic, getDomain, insertComic, listComics, reorderComics, updateComic } from "@/lib/db";
import { currentProfile } from "@/lib/profile";
import { guessTitle, parseInput, sameShape, validatePath } from "@/lib/site";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const noStore = { "cache-control": "no-store" };
const MAX_TITLE = 100;
const MAX_COMICS = 200;

function fail(message: string, status = 400) {
  return NextResponse.json({ error: message }, { status });
}

export async function GET() {
  const user = currentProfile();
  try {
    return NextResponse.json({ comics: await listComics(user) }, { headers: noStore });
  } catch (e) {
    return fail(e instanceof Error ? e.message : "조회 실패", 500);
  }
}

/**
 * 주소 통째로(url) 또는 경로만(path) 받는다.
 * 붙여넣은 주소의 도메인 번호가 저장된 번호와 다르면 domainSuggestion으로 알려준다.
 */
export async function POST(req: Request) {
  const user = currentProfile();

  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail("잘못된 요청입니다.");
  }

  const input = String(body.url ?? body.path ?? "").trim();
  if (!input) return fail("주소를 입력해 주세요.");

  const parsed = parseInput(input);
  if (!parsed) return fail("주소를 알아볼 수 없습니다.");

  const pathError = validatePath(parsed.path);
  if (pathError) return fail(pathError);

  const rawTitle = String(body.title ?? "").trim();
  const title = (rawTitle || guessTitle(parsed.path)).slice(0, MAX_TITLE);

  try {
    const existing = await listComics(user);
    if (existing.length >= MAX_COMICS) return fail("만화는 200개까지 저장할 수 있습니다.");
    if (existing.some((c) => c.path === parsed.path)) return fail("이미 목록에 있는 주소입니다.");

    const comic = await insertComic(user, title, parsed.path);

    // 붙여넣은 주소가 현재 저장된 도메인과 번호만 다르면 갱신을 제안한다.
    let domainSuggestion = null;
    if (parsed.pattern) {
      const cur = await getDomain();
      if (sameShape(cur, parsed.pattern) && cur.num !== parsed.pattern.num) {
        domainSuggestion = parsed.pattern;
      } else if (!sameShape(cur, parsed.pattern)) {
        domainSuggestion = parsed.pattern;
      }
    }
    return NextResponse.json({ comic, domainSuggestion }, { headers: noStore });
  } catch (e) {
    return fail(e instanceof Error ? e.message : "저장 실패", 500);
  }
}

/** { id, title?, path? } 로 수정하거나 { order: [id...] } 로 순서를 바꾼다. */
export async function PATCH(req: Request) {
  const user = currentProfile();

  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail("잘못된 요청입니다.");
  }

  try {
    if (Array.isArray(body.order)) {
      const ids = body.order.filter((v): v is string => typeof v === "string").slice(0, MAX_COMICS);
      await reorderComics(user, ids);
      return NextResponse.json({ comics: await listComics(user) }, { headers: noStore });
    }

    const id = String(body.id ?? "").trim();
    if (!id) return fail("id가 필요합니다.");

    const patch: { title?: string; path?: string } = {};

    if (body.title !== undefined) {
      const title = String(body.title).trim().slice(0, MAX_TITLE);
      if (!title) return fail("제목을 입력해 주세요.");
      patch.title = title;
    }

    if (body.path !== undefined) {
      const parsed = parseInput(String(body.path));
      if (!parsed) return fail("주소를 알아볼 수 없습니다.");
      const pathError = validatePath(parsed.path);
      if (pathError) return fail(pathError);
      patch.path = parsed.path;
    }

    if (patch.title === undefined && patch.path === undefined) return fail("바꿀 내용이 없습니다.");

    const comic = await updateComic(user, id, patch);
    if (!comic) return fail("만화를 찾을 수 없습니다.", 404);
    return NextResponse.json({ comic }, { headers: noStore });
  } catch (e) {
    return fail(e instanceof Error ? e.message : "수정 실패", 500);
  }
}

export async function DELETE(req: Request) {
  const user = currentProfile();

  const id = new URL(req.url).searchParams.get("id");
  if (!id) return fail("id가 필요합니다.");

  try {
    const ok = await deleteComic(user, id);
    if (!ok) return fail("만화를 찾을 수 없습니다.", 404);
    return NextResponse.json({ ok: true }, { headers: noStore });
  } catch (e) {
    return fail(e instanceof Error ? e.message : "삭제 실패", 500);
  }
}
