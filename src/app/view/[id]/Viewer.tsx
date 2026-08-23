"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import type { ComicRow } from "@/lib/db";
import { buildEpisodePath, episodeLabel, parseEpisode } from "@/lib/site";

type Verdict = {
  embeddable: boolean;
  reason: string;
  xFrameOptions?: string;
  frameAncestors?: string;
  status?: number;
  reached: boolean;
};

export default function Viewer({
  comic,
  host,
  url,
  domainNum,
}: {
  comic: ComicRow;
  host: string;
  url: string;
  domainNum: number;
}) {
  const router = useRouter();
  const [verdict, setVerdict] = useState<Verdict | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const ref = parseEpisode(comic.path);
  const label = episodeLabel(comic.path);

  // 브라우저는 삽입이 차단돼도 onload를 부르는 경우가 있어 구분이 안 된다.
  // 그래서 서버에서 응답 헤더를 직접 보고 판정한다.
  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const res = await fetch("/api/embed-check", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ id: comic.id }),
        });
        const data = await res.json();
        if (alive && res.ok) setVerdict(data as Verdict);
      } catch {
        /* 판정 실패해도 iframe은 그대로 띄워본다 */
      }
    })();
    return () => {
      alive = false;
    };
  }, [comic.id]);

  const act = useCallback(
    async (fn: () => Promise<void>) => {
      setBusy(true);
      setError(null);
      try {
        await fn();
        router.refresh();
      } catch (e) {
        setError(e instanceof Error ? e.message : "오류가 발생했습니다.");
      } finally {
        setBusy(false);
      }
    },
    [router],
  );

  const bumpDomain = () =>
    act(async () => {
      const res = await fetch("/api/domain", {
        method: "PATCH",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ delta: 1 }),
      });
      if (!res.ok) throw new Error((await res.json().catch(() => ({})))?.error ?? "변경 실패");
    });

  const setEpisode = (ep: number) =>
    act(async () => {
      if (!ref) throw new Error("이 주소에서는 회차를 찾지 못했습니다.");
      const res = await fetch("/api/comics", {
        method: "PATCH",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ id: comic.id, path: buildEpisodePath(ref, ep) }),
      });
      if (!res.ok) throw new Error((await res.json().catch(() => ({})))?.error ?? "변경 실패");
    });

  return (
    <div className="flex h-[100dvh] flex-col bg-slate-950 text-slate-100">
      <header className="shrink-0 border-b border-slate-800 bg-slate-900">
        <div className="flex items-center gap-2 px-3 py-2">
          <button
            type="button"
            onClick={() => router.push("/")}
            className="h-9 shrink-0 rounded-lg bg-slate-800 px-3 text-sm text-slate-300 active:scale-95"
          >
            ← 목록
          </button>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-semibold">{comic.title}</p>
            <p className="truncate font-mono text-[10px] text-slate-500">
              {host} {label ? `· ${label}` : ""}
            </p>
          </div>
          <a
            href={url}
            target="_blank"
            rel="noopener noreferrer"
            className="h-9 shrink-0 rounded-lg bg-slate-800 px-3 text-sm leading-9 text-slate-300 active:scale-95"
          >
            새 탭
          </a>
        </div>

        <div className="flex items-center gap-1.5 px-3 pb-2">
          <button
            type="button"
            disabled={busy}
            onClick={bumpDomain}
            className="h-9 flex-1 rounded-lg bg-slate-800 text-xs font-semibold text-slate-200 active:scale-95 disabled:opacity-40"
          >
            주소 +1 ({domainNum} → {domainNum + 1})
          </button>
          {ref && (
            <>
              <button
                type="button"
                disabled={busy || ref.ep <= 0}
                onClick={() => setEpisode(ref.ep - 1)}
                className="h-9 w-10 shrink-0 rounded-lg bg-slate-800 text-sm font-bold text-slate-200 active:scale-95 disabled:opacity-30"
                aria-label="이전 화"
              >
                ‹
              </button>
              <button
                type="button"
                disabled={busy}
                onClick={() => setEpisode(ref.ep + 1)}
                className="h-9 shrink-0 rounded-lg bg-sky-500 px-3 text-xs font-bold text-slate-950 active:scale-95 disabled:opacity-40"
              >
                다음 화 ›
              </button>
            </>
          )}
        </div>

        {error && <p className="px-3 pb-2 text-xs text-rose-400">{error}</p>}

        {verdict && !verdict.embeddable && (
          <div className="border-t border-amber-500/30 bg-amber-500/10 px-3 py-2">
            <p className="text-xs leading-relaxed text-amber-200">
              <span className="font-bold">앱 안에서는 안 열립니다.</span> {verdict.reason} 위의{" "}
              <span className="font-semibold">새 탭</span> 으로 열어주세요.
            </p>
            {(verdict.xFrameOptions || verdict.frameAncestors) && (
              <p className="mt-1 break-all font-mono text-[10px] text-amber-300/70">
                {verdict.xFrameOptions ? `X-Frame-Options: ${verdict.xFrameOptions}` : verdict.frameAncestors}
              </p>
            )}
          </div>
        )}
      </header>

      <div className="relative flex-1 bg-slate-900">
        <iframe
          key={url}
          src={url}
          title={comic.title}
          className="absolute inset-0 h-full w-full border-0 bg-white"
          referrerPolicy="no-referrer"
          sandbox="allow-scripts allow-same-origin allow-popups allow-forms"
        />
      </div>
    </div>
  );
}
