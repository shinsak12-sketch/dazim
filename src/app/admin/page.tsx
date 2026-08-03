"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { AdminPledge } from "@/lib/types";
import FitText from "@/components/FitText";

const KEY_STORAGE = "dazim.adminKey";
const POLL_MS = 3000;

export default function AdminPage() {
  const [adminKey, setAdminKey] = useState<string | null>(null);
  const [checking, setChecking] = useState(true);

  // 최초: 저장된 키 검증
  useEffect(() => {
    const stored =
      typeof window !== "undefined" ? window.localStorage.getItem(KEY_STORAGE) : null;
    if (!stored) {
      setChecking(false);
      return;
    }
    (async () => {
      const ok = await verifyKey(stored);
      if (ok) setAdminKey(stored);
      else window.localStorage.removeItem(KEY_STORAGE);
      setChecking(false);
    })();
  }, []);

  if (checking) {
    return (
      <main className="flex h-[100dvh] items-center justify-center bg-slate-950 text-slate-500">
        <div className="animate-pulse text-2xl">불러오는 중…</div>
      </main>
    );
  }

  if (!adminKey) {
    return <KeyGate onSuccess={(k) => setAdminKey(k)} />;
  }

  return <Board adminKey={adminKey} onSignOut={() => {
    window.localStorage.removeItem(KEY_STORAGE);
    setAdminKey(null);
  }} />;
}

async function verifyKey(key: string): Promise<boolean> {
  try {
    const res = await fetch("/api/admin/verify", {
      method: "POST",
      headers: { "x-admin-key": key },
    });
    return res.ok;
  } catch {
    return false;
  }
}

/* ---------------- 관리자 키 입력 ---------------- */

function KeyGate({ onSuccess }: { onSuccess: (key: string) => void }) {
  const [value, setValue] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (busy || !value.trim()) return;
    setBusy(true);
    setError(null);
    const ok = await verifyKey(value.trim());
    if (ok) {
      window.localStorage.setItem(KEY_STORAGE, value.trim());
      onSuccess(value.trim());
    } else {
      setError("관리자 키가 올바르지 않습니다.");
      setBusy(false);
    }
  }

  return (
    <main className="flex h-[100dvh] items-center justify-center bg-slate-950 px-6">
      <form onSubmit={submit} className="w-full max-w-md">
        <h1 className="mb-2 text-3xl font-bold text-white">강사용 화면</h1>
        <p className="mb-8 text-slate-400">관리자 키를 입력해 주세요.</p>
        <input
          type="password"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          placeholder="ADMIN KEY"
          autoFocus
          className="w-full rounded-xl border border-slate-700 bg-slate-900 px-5 py-4 text-xl text-white outline-none focus:border-slate-400"
        />
        {error && <div className="mt-4 text-sm text-red-400">{error}</div>}
        <button
          type="submit"
          disabled={busy}
          className="mt-6 w-full rounded-xl bg-white py-4 text-xl font-bold text-slate-900 disabled:opacity-60"
        >
          {busy ? "확인 중…" : "입장"}
        </button>
      </form>
    </main>
  );
}

/* ---------------- 발표 보드 ---------------- */

function Board({ adminKey, onSignOut }: { adminKey: string; onSignOut: () => void }) {
  const [pledges, setPledges] = useState<AdminPledge[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [overlayIndex, setOverlayIndex] = useState<number | null>(null);
  const [showAll, setShowAll] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const pledgesRef = useRef<AdminPledge[]>([]);

  pledgesRef.current = pledges;

  const load = useCallback(async () => {
    try {
      const res = await fetch("/api/pledges", {
        headers: { "x-admin-key": adminKey },
        cache: "no-store",
      });
      if (!res.ok) return;
      const data = (await res.json()) as { pledges: AdminPledge[] };
      setPledges(data.pledges);
      setLoaded(true);
    } catch {
      /* 폴링 중 일시 오류는 무시 */
    }
  }, [adminKey]);

  // 3초 폴링
  useEffect(() => {
    load();
    const t = setInterval(load, POLL_MS);
    return () => clearInterval(t);
  }, [load]);

  // 전체화면 상태 동기화
  useEffect(() => {
    const onFsChange = () => setIsFullscreen(!!document.fullscreenElement);
    document.addEventListener("fullscreenchange", onFsChange);
    return () => document.removeEventListener("fullscreenchange", onFsChange);
  }, []);

  const reveal = useCallback(
    async (id: string) => {
      // 낙관적 업데이트
      setPledges((prev) => prev.map((p) => (p.id === id ? { ...p, revealed: true } : p)));
      try {
        await fetch(`/api/pledges/${id}`, {
          method: "PATCH",
          headers: { "Content-Type": "application/json", "x-admin-key": adminKey },
          body: JSON.stringify({ revealed: true }),
        });
      } catch {
        /* 무시: 다음 폴링에서 재동기화 */
      }
    },
    [adminKey]
  );

  const openAt = useCallback(
    (index: number) => {
      const p = pledgesRef.current[index];
      if (!p) return;
      setOverlayIndex(index);
      if (!p.revealed) reveal(p.id);
    },
    [reveal]
  );

  const revealedCount = pledges.filter((p) => p.revealed).length;

  const pickRandom = useCallback(() => {
    const list = pledgesRef.current;
    const candidates = list
      .map((p, i) => ({ p, i }))
      .filter(({ p }) => !p.revealed);
    if (candidates.length === 0) return;
    // 인덱스 기반 의사난수(브라우저 crypto 사용, 외부 의존성 없음)
    const rnd = getRandomInt(candidates.length);
    openAt(candidates[rnd].i);
  }, [openAt]);

  async function toggleFullscreen() {
    try {
      if (!document.fullscreenElement) {
        await document.documentElement.requestFullscreen();
      } else {
        await document.exitFullscreen();
      }
    } catch {
      /* 지원 안 하면 무시 */
    }
  }

  return (
    <main className="flex h-[100dvh] flex-col bg-slate-950 text-white">
      {/* 상단 바 */}
      <header className="flex items-center justify-between px-10 pt-8 pb-5">
        <h1 className="text-5xl font-black tracking-tight">초심 다짐</h1>
        <div className="flex items-center gap-6">
          <div className="text-right">
            <div className="text-4xl font-bold tabular-nums">
              {pledges.length}
              <span className="ml-1 text-2xl font-medium text-slate-400">명 도착</span>
            </div>
            <div className="text-lg text-slate-500">공개 {revealedCount} / {pledges.length}</div>
          </div>
          <button
            onClick={onSignOut}
            className="rounded-lg border border-slate-700 px-3 py-2 text-sm text-slate-400 hover:bg-slate-800"
            title="관리자 키 재입력"
          >
            나가기
          </button>
        </div>
      </header>

      {/* 카드 그리드 */}
      <section className="flex-1 overflow-y-auto px-10 pb-28">
        {!loaded ? (
          <div className="flex h-full items-center justify-center text-2xl text-slate-600">
            불러오는 중…
          </div>
        ) : pledges.length === 0 ? (
          <div className="flex h-full flex-col items-center justify-center text-slate-600">
            <div className="text-3xl font-semibold">아직 도착한 다짐이 없습니다</div>
            <div className="mt-2 text-xl">QR을 스캔해 다짐을 남겨 주세요.</div>
          </div>
        ) : (
          <div className="grid grid-cols-2 gap-5 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
            {pledges.map((p, i) => (
              <button
                key={p.id}
                onClick={() => openAt(i)}
                className={[
                  "animate-card-in relative flex aspect-[4/3] flex-col items-center justify-center rounded-2xl border px-4 text-center transition",
                  p.revealed
                    ? "border-slate-800 bg-slate-900/50 opacity-40"
                    : "border-slate-700 bg-slate-800 hover:border-slate-500 hover:bg-slate-700",
                ].join(" ")}
              >
                {p.revealed && (
                  <span className="absolute right-3 top-3 flex h-8 w-8 items-center justify-center rounded-full bg-emerald-500 text-white">
                    <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth={3} strokeLinecap="round" strokeLinejoin="round">
                      <path d="M5 13l4 4L19 7" />
                    </svg>
                  </span>
                )}
                <span className="text-3xl font-bold leading-tight">{p.name}</span>
                <span className="mt-2 text-lg text-slate-400">{p.team}</span>
              </button>
            ))}
          </div>
        )}
      </section>

      {/* 하단 툴바 */}
      <footer className="absolute inset-x-0 bottom-0 flex items-center justify-center gap-4 border-t border-slate-800 bg-slate-950/95 px-10 py-4 backdrop-blur">
        <button
          onClick={pickRandom}
          disabled={pledges.every((p) => p.revealed)}
          className="rounded-xl bg-indigo-600 px-7 py-3 text-xl font-bold hover:bg-indigo-500 disabled:opacity-40"
        >
          🎲 랜덤 뽑기
        </button>
        <button
          onClick={() => setShowAll(true)}
          disabled={pledges.length === 0}
          className="rounded-xl bg-slate-700 px-7 py-3 text-xl font-bold hover:bg-slate-600 disabled:opacity-40"
        >
          전체 보기
        </button>
        <button
          onClick={toggleFullscreen}
          className="rounded-xl border border-slate-700 px-7 py-3 text-xl font-bold hover:bg-slate-800"
        >
          {isFullscreen ? "전체화면 해제" : "전체화면"}
        </button>
      </footer>

      {/* 다짐 오버레이 */}
      {overlayIndex !== null && pledges[overlayIndex] && (
        <PledgeOverlay
          pledges={pledges}
          index={overlayIndex}
          onNavigate={(i) => openAt(i)}
          onClose={() => setOverlayIndex(null)}
        />
      )}

      {/* 전체 보기 오버레이 */}
      {showAll && <AllView pledges={pledges} onClose={() => setShowAll(false)} />}
    </main>
  );
}

/* ---------------- 다짐 전체화면 오버레이 ---------------- */

function PledgeOverlay({
  pledges,
  index,
  onNavigate,
  onClose,
}: {
  pledges: AdminPledge[];
  index: number;
  onNavigate: (i: number) => void;
  onClose: () => void;
}) {
  const p = pledges[index];
  const hasPrev = index > 0;
  const hasNext = index < pledges.length - 1;

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
      else if (e.key === "ArrowLeft" && index > 0) onNavigate(index - 1);
      else if (e.key === "ArrowRight" && index < pledges.length - 1) onNavigate(index + 1);
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [index, pledges.length, onNavigate, onClose]);

  if (!p) return null;

  return (
    <div className="animate-overlay-in fixed inset-0 z-50 flex flex-col bg-slate-950">
      {/* 헤더 */}
      <div className="flex items-start justify-between px-16 pt-12">
        <div>
          <div className="text-6xl font-black">{p.name}</div>
          <div className="mt-3 text-3xl text-indigo-300">{p.team}</div>
        </div>
        <button
          onClick={onClose}
          className="rounded-xl border border-slate-700 px-6 py-3 text-2xl font-bold text-slate-300 hover:bg-slate-800"
        >
          ✕ 닫기
        </button>
      </div>

      {/* 본문 (자동 크기 조절) */}
      <div className="relative flex flex-1 items-center px-8 py-8">
        {/* 이전 */}
        <button
          onClick={() => hasPrev && onNavigate(index - 1)}
          disabled={!hasPrev}
          aria-label="이전"
          className="flex h-20 w-20 flex-none items-center justify-center rounded-full bg-slate-800/70 text-4xl text-white hover:bg-slate-700 disabled:opacity-20"
        >
          ‹
        </button>

        <div className="mx-6 flex h-full flex-1 items-center">
          <FitText
            key={p.id}
            text={p.content}
            min={24}
            max={140}
            className="w-full whitespace-pre-wrap break-words text-center font-semibold text-white"
          />
        </div>

        {/* 다음 */}
        <button
          onClick={() => hasNext && onNavigate(index + 1)}
          disabled={!hasNext}
          aria-label="다음"
          className="flex h-20 w-20 flex-none items-center justify-center rounded-full bg-slate-800/70 text-4xl text-white hover:bg-slate-700 disabled:opacity-20"
        >
          ›
        </button>
      </div>

      {/* 하단 위치 표시 */}
      <div className="pb-10 text-center text-2xl text-slate-500 tabular-nums">
        {index + 1} / {pledges.length}
      </div>
    </div>
  );
}

/* ---------------- 전체 보기 ---------------- */

function AllView({ pledges, onClose }: { pledges: AdminPledge[]; onClose: () => void }) {
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="animate-overlay-in fixed inset-0 z-50 flex flex-col bg-slate-950">
      <div className="flex items-center justify-between px-16 py-8">
        <h2 className="text-5xl font-black">전체 다짐 ({pledges.length})</h2>
        <button
          onClick={onClose}
          className="rounded-xl border border-slate-700 px-6 py-3 text-2xl font-bold text-slate-300 hover:bg-slate-800"
        >
          ✕ 닫기
        </button>
      </div>
      <div className="flex-1 overflow-y-auto px-16 pb-16">
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
          {pledges.map((p) => (
            <div
              key={p.id}
              className="rounded-2xl border border-slate-800 bg-slate-900 p-8"
            >
              <div className="flex items-baseline gap-3">
                <span className="text-3xl font-bold">{p.name}</span>
                <span className="text-xl text-indigo-300">{p.team}</span>
                {p.revealed && <span className="text-lg text-emerald-400">· 공개됨</span>}
              </div>
              <p className="mt-4 whitespace-pre-wrap break-words text-2xl leading-relaxed text-slate-200">
                {p.content}
              </p>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

/* ---------------- 유틸 ---------------- */

function getRandomInt(max: number): number {
  if (max <= 0) return 0;
  if (typeof window !== "undefined" && window.crypto?.getRandomValues) {
    const arr = new Uint32Array(1);
    window.crypto.getRandomValues(arr);
    return arr[0] % max;
  }
  // 폴백 없음(브라우저 환경 보장). 안전상 0 반환.
  return 0;
}
