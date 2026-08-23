"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import type { ComicRow } from "@/lib/db";
import {
  buildEpisodePath,
  buildHost,
  buildUrl,
  episodeLabel,
  parseEpisode,
  parseHost,
  prettyPath,
  sameShape,
  type DomainPattern,
} from "@/lib/site";

type Domain = DomainPattern & { host: string; updated_at: string; updated_by: string };

type ProbeResult = {
  num: number;
  host: string;
  state: "alive" | "dead" | "error";
  status?: number;
  finalHost?: string;
  title?: string;
  reason?: string;
};

type ScanRow = ProbeResult & { reach: "idle" | "checking" | "ok" | "blocked" };
type Reach = "idle" | "checking" | "ok" | "blocked";

const CLIENT_TIMEOUT_MS = 6000;
const IN_APP_KEY = "toon.openInApp";
/** 새 탭으로 열고 이만큼 지나서 돌아오면 "다 봤냐"고 물어본다. */
const READ_PROMPT_MS = 15000;

/** 내 회선에서 실제로 닿는지 확인. 응답 내용은 못 읽지만 연결 성공/실패는 구분된다. */
async function reachable(host: string): Promise<boolean> {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), CLIENT_TIMEOUT_MS);
  try {
    await fetch(`https://${host}/favicon.ico?_=${Date.now()}`, {
      mode: "no-cors",
      cache: "no-store",
      credentials: "omit",
      signal: ctl.signal,
    });
    return true;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

async function api(path: string, init?: RequestInit) {
  const res = await fetch(path, {
    ...init,
    headers: { "content-type": "application/json", ...(init?.headers ?? {}) },
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data?.error ?? "요청이 실패했습니다.");
  return data;
}

export default function ComicList({
  user,
  initialDomain,
  initialComics,
}: {
  user: string;
  initialDomain: Domain;
  initialComics: ComicRow[];
}) {
  const router = useRouter();
  const [domain, setDomain] = useState<Domain>(initialDomain);
  const [comics, setComics] = useState<ComicRow[]>(initialComics);
  const [reach, setReach] = useState<Reach>("idle");
  const [error, setError] = useState<string | null>(null);
  const [editMode, setEditMode] = useState(false);
  const [busy, setBusy] = useState(false);
  const [inApp, setInApp] = useState(false);

  const [showAdd, setShowAdd] = useState(false);
  const [showDomainEdit, setShowDomainEdit] = useState(false);
  const [scanRows, setScanRows] = useState<ScanRow[] | null>(null);
  const [scanning, setScanning] = useState(false);
  const [suggestion, setSuggestion] = useState<DomainPattern | null>(null);

  /** 만화별 최신화 확인 결과 */
  const [latest, setLatest] = useState<Record<string, number>>({});
  const [checking, setChecking] = useState<string | null>(null);
  /** 새 탭으로 연 만화 — 돌아왔을 때 다음 화를 물어보기 위해 */
  const pending = useRef<{ id: string; at: number } | null>(null);
  const [askNext, setAskNext] = useState<ComicRow | null>(null);

  useEffect(() => {
    try {
      setInApp(localStorage.getItem(IN_APP_KEY) === "1");
    } catch {
      /* 프라이빗 모드 등 */
    }
  }, []);

  const toggleInApp = useCallback((v: boolean) => {
    setInApp(v);
    try {
      localStorage.setItem(IN_APP_KEY, v ? "1" : "0");
    } catch {
      /* noop */
    }
  }, []);

  // 화면을 열면 현재 도메인이 내 회선에서 열리는지만 조용히 확인한다. (열기는 사용자가 누른다)
  const checkReach = useCallback(async (host: string) => {
    setReach("checking");
    setReach((await reachable(host)) ? "ok" : "blocked");
  }, []);

  useEffect(() => {
    void checkReach(domain.host);
  }, [domain.host, checkReach]);

  const run = useCallback(async (fn: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
    } catch (e) {
      setError(e instanceof Error ? e.message : "오류가 발생했습니다.");
    } finally {
      setBusy(false);
    }
  }, []);

  const patchDomain = useCallback(
    (body: Record<string, unknown>) =>
      run(async () => {
        const data = await api("/api/domain", { method: "PATCH", body: JSON.stringify(body) });
        setDomain(data.domain as Domain);
        setScanRows(null);
        setSuggestion(null);
      }),
    [run],
  );

  const scan = useCallback(
    () =>
      run(async () => {
        setScanning(true);
        setScanRows(null);
        try {
          const data = await api("/api/probe", {
            method: "POST",
            body: JSON.stringify({ from: domain.num, count: 12 }),
          });
          const results = data.results as ProbeResult[];
          setScanRows(results.map((r) => ({ ...r, reach: r.state === "alive" ? "checking" : "idle" })));

          // 서버가 살아있다고 한 후보만 내 회선에서 재확인한다.
          for (const r of results.filter((x) => x.state === "alive")) {
            const ok = await reachable(r.host);
            setScanRows((prev) =>
              prev ? prev.map((row) => (row.host === r.host ? { ...row, reach: ok ? "ok" : "blocked" } : row)) : prev,
            );
          }
        } finally {
          setScanning(false);
        }
      }),
    [domain.num, run],
  );

  const addComic = useCallback(
    (url: string, title: string) =>
      run(async () => {
        const data = await api("/api/comics", { method: "POST", body: JSON.stringify({ url, title }) });
        setComics((prev) => [...prev, data.comic as ComicRow]);
        setShowAdd(false);
        if (data.domainSuggestion) setSuggestion(data.domainSuggestion as DomainPattern);
      }),
    [run],
  );

  const saveComic = useCallback(
    (id: string, title: string, path: string) =>
      run(async () => {
        const data = await api("/api/comics", { method: "PATCH", body: JSON.stringify({ id, title, path }) });
        setComics((prev) => prev.map((c) => (c.id === id ? (data.comic as ComicRow) : c)));
      }),
    [run],
  );

  /** 회차만 바꿔 저장한다. 경로의 숫자를 갈아끼우는 것이라 별도 컬럼이 필요 없다. */
  const setEpisode = useCallback(
    (comic: ComicRow, ep: number) =>
      run(async () => {
        const ref = parseEpisode(comic.path);
        if (!ref) throw new Error("이 주소에서는 회차 번호를 찾지 못했습니다.");
        if (ep < 0) return;
        const data = await api("/api/comics", {
          method: "PATCH",
          body: JSON.stringify({ id: comic.id, path: buildEpisodePath(ref, ep) }),
        });
        setComics((prev) => prev.map((c) => (c.id === comic.id ? (data.comic as ComicRow) : c)));
        setAskNext(null);
      }),
    [run],
  );

  const checkLatest = useCallback(
    (comic: ComicRow) =>
      run(async () => {
        setChecking(comic.id);
        try {
          const data = await api("/api/episode", {
            method: "POST",
            body: JSON.stringify({ id: comic.id, count: 8 }),
          });
          setLatest((prev) => ({ ...prev, [comic.id]: Number(data.latest) }));
        } finally {
          setChecking(null);
        }
      }),
    [run],
  );

  const removeComic = useCallback(
    (c: ComicRow) =>
      run(async () => {
        if (!window.confirm(`"${c.title}" 을(를) 목록에서 지울까요?`)) return;
        await api(`/api/comics?id=${encodeURIComponent(c.id)}`, { method: "DELETE" });
        setComics((prev) => prev.filter((x) => x.id !== c.id));
      }),
    [run],
  );

  const move = useCallback(
    (index: number, dir: -1 | 1) => {
      const target = index + dir;
      if (target < 0 || target >= comics.length) return;
      const next = [...comics];
      [next[index], next[target]] = [next[target], next[index]];
      setComics(next);
      void run(async () => {
        await api("/api/comics", { method: "PATCH", body: JSON.stringify({ order: next.map((c) => c.id) }) });
      });
    },
    [comics, run],
  );

  /** 새 탭으로 나갔다가 돌아오면 다음 화로 넘길지 물어본다. */
  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState !== "visible") return;
      const p = pending.current;
      if (!p || Date.now() - p.at < READ_PROMPT_MS) return;
      const comic = comics.find((c) => c.id === p.id);
      pending.current = null;
      if (comic && parseEpisode(comic.path)) setAskNext(comic);
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => document.removeEventListener("visibilitychange", onVisible);
  }, [comics]);

  const markOpened = useCallback((c: ComicRow) => {
    pending.current = { id: c.id, at: Date.now() };
  }, []);

  const logout = useCallback(async () => {
    await fetch("/api/logout", { method: "POST" });
    router.replace("/login");
    router.refresh();
  }, [router]);

  // 리다이렉트 최종 주소가 같은 모양의 다른 번호면 그게 정답일 가능성이 높다.
  const redirectHint = useMemo(() => {
    for (const row of scanRows ?? []) {
      if (!row.finalHost) continue;
      const p = parseHost(row.finalHost);
      if (p && sameShape(p, domain) && p.num !== domain.num) return p.num;
    }
    return null;
  }, [scanRows, domain]);

  return (
    <main className="min-h-screen bg-slate-950 pb-24 text-slate-100">
      <div className="mx-auto w-full max-w-xl px-4 py-5">
        <header className="flex items-center justify-between">
          <h1 className="text-lg font-bold tracking-tight">만화 바로가기</h1>
          <button
            type="button"
            onClick={logout}
            className="rounded-lg bg-slate-900 px-3 py-1.5 text-xs text-slate-400 ring-1 ring-slate-800"
          >
            {user} · 나가기
          </button>
        </header>

        <DomainBar
          domain={domain}
          reach={reach}
          busy={busy}
          scanning={scanning}
          onBump={(d) => patchDomain({ delta: d })}
          onRecheck={() => checkReach(domain.host)}
          onScan={scan}
          onToggleEdit={() => setShowDomainEdit((v) => !v)}
          editing={showDomainEdit}
        />

        {showDomainEdit && (
          <DomainEditor
            domain={domain}
            busy={busy}
            onSave={(p) => patchDomain(p as unknown as Record<string, unknown>)}
            onClose={() => setShowDomainEdit(false)}
          />
        )}

        {suggestion && (
          <div className="mt-3 rounded-xl bg-sky-500/10 p-3 text-sm ring-1 ring-sky-500/40">
            <p className="text-sky-200">
              붙여넣은 주소는 <span className="font-mono">{buildHost(suggestion)}</span> 입니다.
            </p>
            <div className="mt-2 flex gap-2">
              <button
                type="button"
                onClick={() => patchDomain(suggestion as unknown as Record<string, unknown>)}
                className="rounded-lg bg-sky-500 px-3 py-1.5 text-xs font-bold text-slate-950"
              >
                이 주소로 갱신
              </button>
              <button
                type="button"
                onClick={() => setSuggestion(null)}
                className="rounded-lg bg-slate-800 px-3 py-1.5 text-xs text-slate-300"
              >
                그대로 두기
              </button>
            </div>
          </div>
        )}

        {error && (
          <p className="mt-3 rounded-xl bg-rose-500/10 p-3 text-sm text-rose-300 ring-1 ring-rose-500/30">{error}</p>
        )}

        {scanRows && (
          <ScanPanel
            rows={scanRows}
            scanning={scanning}
            current={domain.num}
            redirectHint={redirectHint}
            onPick={(num) => patchDomain({ num })}
            onClose={() => setScanRows(null)}
          />
        )}

        <section className="mt-5 space-y-2">
          {comics.length === 0 && (
            <p className="rounded-xl bg-slate-900 p-6 text-center text-sm text-slate-500 ring-1 ring-slate-800">
              아직 저장한 만화가 없습니다.
              <br />
              아래 <span className="text-slate-300">만화 추가</span> 를 눌러 보던 주소를 붙여넣어 주세요.
            </p>
          )}

          {comics.map((c, i) => (
            <ComicCard
              key={c.id}
              comic={c}
              url={buildUrl(domain, c.path)}
              inApp={inApp}
              editMode={editMode}
              first={i === 0}
              last={i === comics.length - 1}
              busy={busy}
              latest={latest[c.id]}
              checking={checking === c.id}
              onOpenInApp={() => router.push(`/view/${c.id}`)}
              onOpened={() => markOpened(c)}
              onMove={(dir) => move(i, dir)}
              onSave={(title, path) => saveComic(c.id, title, path)}
              onDelete={() => removeComic(c)}
              onSetEpisode={(ep) => setEpisode(c, ep)}
              onCheckLatest={() => checkLatest(c)}
            />
          ))}
        </section>

        <label className="mt-4 flex items-center gap-2 rounded-xl bg-slate-900 px-3 py-2.5 text-xs text-slate-400 ring-1 ring-slate-800">
          <input
            type="checkbox"
            checked={inApp}
            onChange={(e) => toggleInApp(e.target.checked)}
            className="h-4 w-4 accent-sky-500"
          />
          앱 안에서 열기 (실험적 — 사이트가 막아두면 빈 화면이 뜹니다)
        </label>

        <div className="mt-3 flex gap-2">
          <button
            type="button"
            onClick={() => setShowAdd(true)}
            className="flex-1 rounded-xl bg-sky-500 py-3 text-sm font-bold text-slate-950 active:scale-[0.99]"
          >
            + 만화 추가
          </button>
          {comics.length > 0 && (
            <button
              type="button"
              onClick={() => setEditMode((v) => !v)}
              className={`rounded-xl px-5 py-3 text-sm font-semibold active:scale-[0.99] ${
                editMode ? "bg-slate-100 text-slate-900" : "bg-slate-900 text-slate-300 ring-1 ring-slate-800"
              }`}
            >
              {editMode ? "완료" : "편집"}
            </button>
          )}
        </div>

        <p className="mt-6 text-[11px] leading-relaxed text-slate-600">
          도메인 번호는 두 계정이 함께 씁니다. 마지막 변경: {domain.updated_by || "-"}
        </p>
      </div>

      {showAdd && <AddSheet busy={busy} onAdd={addComic} onClose={() => setShowAdd(false)} />}

      {askNext && (
        <NextEpisodeBar
          comic={askNext}
          busy={busy}
          onYes={() => {
            const ref = parseEpisode(askNext.path);
            if (ref) void setEpisode(askNext, ref.ep + 1);
          }}
          onNo={() => setAskNext(null)}
        />
      )}
    </main>
  );
}

function NextEpisodeBar({
  comic,
  busy,
  onYes,
  onNo,
}: {
  comic: ComicRow;
  busy: boolean;
  onYes: () => void;
  onNo: () => void;
}) {
  const label = episodeLabel(comic.path);
  return (
    <div className="fixed inset-x-0 bottom-0 z-20 border-t border-slate-800 bg-slate-900 px-4 py-3">
      <div className="mx-auto flex w-full max-w-xl items-center gap-2">
        <p className="min-w-0 flex-1 truncate text-sm text-slate-200">
          <span className="font-semibold">{label}</span> 다 보셨나요?
        </p>
        <button
          type="button"
          onClick={onNo}
          className="shrink-0 rounded-lg bg-slate-800 px-3 py-2 text-xs text-slate-300"
        >
          아니요
        </button>
        <button
          type="button"
          disabled={busy}
          onClick={onYes}
          className="shrink-0 rounded-lg bg-sky-500 px-3 py-2 text-xs font-bold text-slate-950 disabled:opacity-40"
        >
          다음 화로
        </button>
      </div>
    </div>
  );
}

function DomainBar({
  domain,
  reach,
  busy,
  scanning,
  onBump,
  onRecheck,
  onScan,
  onToggleEdit,
  editing,
}: {
  domain: Domain;
  reach: Reach;
  busy: boolean;
  scanning: boolean;
  onBump: (delta: number) => void;
  onRecheck: () => void;
  onScan: () => void;
  onToggleEdit: () => void;
  editing: boolean;
}) {
  const badge =
    reach === "ok"
      ? { text: "접속 가능", cls: "bg-emerald-500/20 text-emerald-300" }
      : reach === "blocked"
        ? { text: "안 열림", cls: "bg-rose-500/20 text-rose-300" }
        : { text: "확인 중", cls: "bg-slate-800 text-slate-400" };

  return (
    <section className="mt-4 rounded-2xl bg-slate-900 p-4 ring-1 ring-slate-800">
      <div className="flex items-center gap-2">
        <span className="text-xs font-semibold uppercase tracking-wide text-slate-500">현재 주소</span>
        <button type="button" onClick={onRecheck} className={`rounded px-1.5 py-0.5 text-[11px] font-semibold ${badge.cls}`}>
          {badge.text}
        </button>
        <button type="button" onClick={onToggleEdit} className="ml-auto text-xs text-slate-500 underline">
          {editing ? "닫기" : "주소 모양 수정"}
        </button>
      </div>

      <div className="mt-3 flex items-center gap-2">
        <button
          type="button"
          disabled={busy}
          onClick={() => onBump(-1)}
          className="h-14 w-14 shrink-0 rounded-xl bg-slate-800 text-2xl font-bold text-slate-200 active:scale-95 disabled:opacity-40"
          aria-label="번호 내리기"
        >
          −
        </button>
        <div className="flex-1 truncate rounded-xl bg-slate-950 px-2 py-4 text-center font-mono text-base ring-1 ring-slate-800">
          {domain.host}
        </div>
        <button
          type="button"
          disabled={busy}
          onClick={() => onBump(1)}
          className="h-14 w-14 shrink-0 rounded-xl bg-sky-500 text-2xl font-bold text-slate-950 active:scale-95 disabled:opacity-40"
          aria-label="번호 올리기"
        >
          +
        </button>
      </div>

      <button
        type="button"
        onClick={onScan}
        disabled={busy || scanning}
        className="mt-3 w-full rounded-xl bg-slate-800 py-2.5 text-sm font-semibold text-slate-200 active:scale-[0.99] disabled:opacity-40"
      >
        {scanning ? "찾는 중…" : "되는 번호 자동으로 찾기"}
      </button>
    </section>
  );
}

function DomainEditor({
  domain,
  busy,
  onSave,
  onClose,
}: {
  domain: Domain;
  busy: boolean;
  onSave: (p: DomainPattern) => void;
  onClose: () => void;
}) {
  const [prefix, setPrefix] = useState(domain.prefix);
  const [suffix, setSuffix] = useState(domain.suffix);
  const [num, setNum] = useState(String(domain.num));
  const [pad, setPad] = useState(String(domain.pad));

  const preview = buildHost({
    head: domain.head,
    prefix,
    suffix,
    pad: Number(pad) || 0,
    num: Number(num) || 0,
  });

  return (
    <section className="mt-2 rounded-2xl bg-slate-900 p-4 ring-1 ring-slate-800">
      <div className="grid grid-cols-4 gap-2">
        <Field label="앞부분" value={prefix} onChange={setPrefix} className="col-span-2" />
        <Field label="번호" value={num} onChange={setNum} inputMode="numeric" />
        <Field label="자릿수" value={pad} onChange={setPad} inputMode="numeric" />
        <Field label="뒷부분" value={suffix} onChange={setSuffix} className="col-span-4" />
      </div>
      <p className="mt-3 truncate font-mono text-xs text-slate-400">→ {preview}</p>
      <div className="mt-3 flex gap-2">
        <button
          type="button"
          disabled={busy}
          onClick={() => {
            onSave({ head: domain.head, prefix, suffix, pad: Number(pad) || 0, num: Number(num) || 0 });
            onClose();
          }}
          className="flex-1 rounded-xl bg-sky-500 py-2.5 text-sm font-bold text-slate-950 disabled:opacity-40"
        >
          저장
        </button>
        <button type="button" onClick={onClose} className="rounded-xl bg-slate-800 px-5 py-2.5 text-sm text-slate-300">
          취소
        </button>
      </div>
    </section>
  );
}

function Field({
  label,
  value,
  onChange,
  className = "",
  inputMode,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  className?: string;
  inputMode?: "numeric" | "text";
}) {
  return (
    <label className={`block ${className}`}>
      <span className="block text-[11px] text-slate-500">{label}</span>
      <input
        value={value}
        inputMode={inputMode}
        autoCapitalize="off"
        autoCorrect="off"
        spellCheck={false}
        onChange={(e) => onChange(e.target.value)}
        className="mt-1 w-full rounded-lg bg-slate-950 px-2 py-2 font-mono text-sm text-slate-100 outline-none ring-1 ring-slate-800 focus:ring-2 focus:ring-sky-500"
      />
    </label>
  );
}

function ScanPanel({
  rows,
  scanning,
  current,
  redirectHint,
  onPick,
  onClose,
}: {
  rows: ScanRow[];
  scanning: boolean;
  current: number;
  redirectHint: number | null;
  onPick: (num: number) => void;
  onClose: () => void;
}) {
  const best = rows.find((r) => r.state === "alive" && r.reach === "ok");

  return (
    <section className="mt-3 rounded-2xl bg-slate-900 p-4 ring-1 ring-slate-800">
      <div className="flex items-center">
        <h2 className="text-sm font-bold">자동 찾기 결과</h2>
        <button type="button" onClick={onClose} className="ml-auto text-xs text-slate-500 underline">
          닫기
        </button>
      </div>

      {redirectHint !== null && redirectHint !== current && (
        <button
          type="button"
          onClick={() => onPick(redirectHint)}
          className="mt-3 w-full rounded-xl bg-sky-500/15 p-3 text-left text-sm text-sky-200 ring-1 ring-sky-500/40"
        >
          사이트가 <span className="font-mono font-bold">{redirectHint}</span> 번으로 안내하고 있습니다 — 눌러서 적용
        </button>
      )}

      {best && (
        <button
          type="button"
          onClick={() => onPick(best.num)}
          className="mt-3 w-full rounded-xl bg-emerald-400 py-3 text-sm font-bold text-slate-950"
        >
          {best.host} 로 바꾸기 (지금 접속됨)
        </button>
      )}

      {!scanning && !best && redirectHint === null && (
        <p className="mt-3 text-sm text-amber-300">지금 열리는 번호를 못 찾았습니다. 아래에서 직접 골라보세요.</p>
      )}

      <ul className="mt-3 space-y-1.5">
        {rows.map((r) => (
          <li key={r.host} className="flex items-center gap-2 rounded-lg bg-slate-950 px-3 py-2 ring-1 ring-slate-800">
            <span className={`flex-1 truncate font-mono text-xs ${r.state === "dead" ? "text-slate-600" : "text-slate-200"}`}>
              {r.host}
            </span>
            <Badge state={r.state} reach={r.reach} reason={r.reason} />
            {r.state === "alive" && r.num !== current && (
              <button
                type="button"
                onClick={() => onPick(r.num)}
                className="shrink-0 rounded bg-slate-800 px-2 py-1 text-[11px] font-semibold text-slate-200"
              >
                선택
              </button>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

function Badge({ state, reach, reason }: { state: ScanRow["state"]; reach: ScanRow["reach"]; reason?: string }) {
  if (state !== "alive") {
    return <span className="shrink-0 text-[11px] text-slate-600">{reason ?? "없음"}</span>;
  }
  const map = {
    ok: { text: "열림", cls: "bg-emerald-500/20 text-emerald-300" },
    blocked: { text: "막힘", cls: "bg-rose-500/20 text-rose-300" },
    checking: { text: "확인중", cls: "bg-slate-800 text-slate-400" },
    idle: { text: "있음", cls: "bg-sky-500/20 text-sky-300" },
  } as const;
  const b = map[reach];
  return <span className={`shrink-0 rounded px-1.5 py-0.5 text-[11px] font-semibold ${b.cls}`}>{b.text}</span>;
}

function ComicCard({
  comic,
  url,
  inApp,
  editMode,
  first,
  last,
  busy,
  latest,
  checking,
  onOpenInApp,
  onOpened,
  onMove,
  onSave,
  onDelete,
  onSetEpisode,
  onCheckLatest,
}: {
  comic: ComicRow;
  url: string;
  inApp: boolean;
  editMode: boolean;
  first: boolean;
  last: boolean;
  busy: boolean;
  latest?: number;
  checking: boolean;
  onOpenInApp: () => void;
  onOpened: () => void;
  onMove: (dir: -1 | 1) => void;
  onSave: (title: string, path: string) => void;
  onDelete: () => void;
  onSetEpisode: (ep: number) => void;
  onCheckLatest: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [showEp, setShowEp] = useState(false);
  const [title, setTitle] = useState(comic.title);
  const [path, setPath] = useState(prettyPath(comic.path));

  const ref = parseEpisode(comic.path);
  const label = episodeLabel(comic.path);
  const behind = latest !== undefined && ref ? latest - ref.ep : 0;

  useEffect(() => {
    setTitle(comic.title);
    setPath(prettyPath(comic.path));
  }, [comic.title, comic.path]);

  if (editing) {
    return (
      <div className="rounded-xl bg-slate-900 p-3 ring-1 ring-sky-500/40">
        <Field label="제목" value={title} onChange={setTitle} />
        <label className="mt-2 block">
          <span className="block text-[11px] text-slate-500">주소 (전체 주소를 붙여넣어도 됩니다)</span>
          <textarea
            value={path}
            onChange={(e) => setPath(e.target.value)}
            rows={3}
            spellCheck={false}
            autoCapitalize="off"
            autoCorrect="off"
            className="mt-1 w-full resize-none break-all rounded-lg bg-slate-950 px-2 py-2 font-mono text-[12px] text-slate-100 outline-none ring-1 ring-slate-800 focus:ring-2 focus:ring-sky-500"
          />
        </label>
        <div className="mt-2 flex gap-2">
          <button
            type="button"
            disabled={busy || !title.trim() || !path.trim()}
            onClick={() => {
              onSave(title.trim(), path.trim());
              setEditing(false);
            }}
            className="flex-1 rounded-lg bg-sky-500 py-2 text-sm font-bold text-slate-950 disabled:opacity-40"
          >
            저장
          </button>
          <button
            type="button"
            onClick={() => {
              setTitle(comic.title);
              setPath(prettyPath(comic.path));
              setEditing(false);
            }}
            className="rounded-lg bg-slate-800 px-4 py-2 text-sm text-slate-300"
          >
            취소
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="rounded-xl bg-slate-900 p-3 ring-1 ring-slate-800">
      <div className="flex items-center gap-2">
        <div className="min-w-0 flex-1">
          <p className="truncate text-[15px] font-semibold text-slate-100">{comic.title}</p>
          <div className="mt-0.5 flex items-center gap-1.5">
            {label ? (
              <button
                type="button"
                onClick={() => setShowEp((v) => !v)}
                className="rounded bg-slate-800 px-1.5 py-0.5 text-[11px] font-semibold text-sky-300"
              >
                {label} {showEp ? "▴" : "▾"}
              </button>
            ) : (
              <span className="truncate font-mono text-[11px] text-slate-500">{prettyPath(comic.path)}</span>
            )}
            {behind > 0 && <span className="text-[11px] font-semibold text-emerald-400">+{behind}화 새로 나옴</span>}
            {latest !== undefined && behind <= 0 && <span className="text-[11px] text-slate-500">최신화</span>}
          </div>
        </div>

        {editMode ? (
          <div className="flex shrink-0 items-center gap-1">
            <IconBtn label="위로" disabled={first} onClick={() => onMove(-1)}>
              ↑
            </IconBtn>
            <IconBtn label="아래로" disabled={last} onClick={() => onMove(1)}>
              ↓
            </IconBtn>
            <IconBtn label="수정" onClick={() => setEditing(true)}>
              ✎
            </IconBtn>
            <IconBtn label="삭제" danger onClick={onDelete}>
              ✕
            </IconBtn>
          </div>
        ) : inApp ? (
          <button
            type="button"
            onClick={onOpenInApp}
            className="shrink-0 rounded-lg bg-sky-500 px-4 py-2.5 text-sm font-bold text-slate-950 active:scale-95"
          >
            열기
          </button>
        ) : (
          <a
            href={url}
            target="_blank"
            rel="noopener noreferrer"
            onClick={onOpened}
            className="shrink-0 rounded-lg bg-sky-500 px-4 py-2.5 text-sm font-bold text-slate-950 active:scale-95"
          >
            열기
          </a>
        )}
      </div>

      {showEp && ref && !editMode && (
        <div className="mt-2 flex items-center gap-1.5 border-t border-slate-800 pt-2">
          <button
            type="button"
            disabled={busy || ref.ep <= 0}
            onClick={() => onSetEpisode(ref.ep - 1)}
            className="h-9 w-10 shrink-0 rounded-lg bg-slate-800 text-sm font-bold text-slate-200 active:scale-95 disabled:opacity-30"
            aria-label="이전 화"
          >
            ‹
          </button>
          <button
            type="button"
            disabled={busy}
            onClick={() => onSetEpisode(ref.ep + 1)}
            className="h-9 flex-1 rounded-lg bg-slate-800 text-xs font-semibold text-slate-200 active:scale-95 disabled:opacity-40"
          >
            다음 화 ({ref.ep} → {ref.ep + 1})
          </button>
          <button
            type="button"
            disabled={busy || checking}
            onClick={onCheckLatest}
            className="h-9 shrink-0 rounded-lg bg-slate-800 px-3 text-xs font-semibold text-slate-300 active:scale-95 disabled:opacity-40"
          >
            {checking ? "확인 중…" : "최신화 확인"}
          </button>
          {latest !== undefined && latest > ref.ep && (
            <button
              type="button"
              disabled={busy}
              onClick={() => onSetEpisode(latest)}
              className="h-9 shrink-0 rounded-lg bg-emerald-400 px-3 text-xs font-bold text-slate-950 active:scale-95"
            >
              {latest}화로
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function IconBtn({
  children,
  label,
  onClick,
  disabled,
  danger,
}: {
  children: React.ReactNode;
  label: string;
  onClick: () => void;
  disabled?: boolean;
  danger?: boolean;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      disabled={disabled}
      onClick={onClick}
      className={`h-9 w-9 rounded-lg text-sm active:scale-95 disabled:opacity-25 ${
        danger ? "bg-rose-500/20 text-rose-300" : "bg-slate-800 text-slate-300"
      }`}
    >
      {children}
    </button>
  );
}

function AddSheet({
  busy,
  onAdd,
  onClose,
}: {
  busy: boolean;
  onAdd: (url: string, title: string) => void;
  onClose: () => void;
}) {
  const [url, setUrl] = useState("");
  const [title, setTitle] = useState("");

  return (
    <div className="fixed inset-0 z-10 flex items-end bg-black/60 sm:items-center sm:justify-center" onClick={onClose}>
      <div
        className="w-full rounded-t-2xl bg-slate-900 p-4 ring-1 ring-slate-800 sm:max-w-md sm:rounded-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="text-base font-bold">만화 추가</h2>
        <p className="mt-1 text-xs text-slate-500">보던 페이지 주소를 통째로 붙여넣으세요.</p>

        <textarea
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          rows={3}
          autoFocus
          spellCheck={false}
          autoCapitalize="off"
          autoCorrect="off"
          placeholder="https://tkor146.com/..."
          className="mt-3 w-full resize-none break-all rounded-xl bg-slate-950 p-3 font-mono text-[12px] text-slate-100 outline-none ring-1 ring-slate-800 focus:ring-2 focus:ring-sky-500"
        />

        <Field label="제목 (비워두면 주소에서 자동 추출)" value={title} onChange={setTitle} />

        <div className="mt-4 flex gap-2">
          <button
            type="button"
            disabled={busy || !url.trim()}
            onClick={() => onAdd(url.trim(), title.trim())}
            className="flex-1 rounded-xl bg-sky-500 py-3 text-sm font-bold text-slate-950 disabled:opacity-40"
          >
            추가
          </button>
          <button type="button" onClick={onClose} className="rounded-xl bg-slate-800 px-5 py-3 text-sm text-slate-300">
            취소
          </button>
        </div>
      </div>
    </div>
  );
}
