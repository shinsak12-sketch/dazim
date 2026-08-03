"use client";

import { useEffect, useRef, useState } from "react";
import { LIMITS } from "@/lib/validation";
import type { Pledge } from "@/lib/types";

const STORAGE_KEY = "dazim.submissionId";

type View = "loading" | "form" | "done";

export default function InputPage() {
  const [view, setView] = useState<View>("loading");
  const [name, setName] = useState("");
  const [team, setTeam] = useState("");
  const [content, setContent] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 제출 완료된 다짐(완료 화면 표시용)
  const [submitted, setSubmitted] = useState<Pledge | null>(null);
  // 수정 모드 여부(true면 기존 id를 PATCH)
  const [editing, setEditing] = useState(false);
  const idRef = useRef<string | null>(null);

  // 최초 마운트: localStorage 확인 후 기존 제출 복원
  useEffect(() => {
    const stored =
      typeof window !== "undefined" ? window.localStorage.getItem(STORAGE_KEY) : null;
    if (!stored) {
      setView("form");
      return;
    }
    idRef.current = stored;
    (async () => {
      try {
        const res = await fetch(`/api/pledges/${stored}`, { cache: "no-store" });
        if (res.ok) {
          const data = (await res.json()) as { pledge: Pledge };
          setSubmitted(data.pledge);
          setName(data.pledge.name);
          setTeam(data.pledge.team);
          setContent(data.pledge.content);
          setView("done");
        } else {
          // 서버에 없으면 저장값 폐기
          window.localStorage.removeItem(STORAGE_KEY);
          idRef.current = null;
          setView("form");
        }
      } catch {
        setView("form");
      }
    })();
  }, []);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (submitting) return;
    setError(null);

    const payload = { name: name.trim(), team: team.trim(), content: content.trim() };
    if (!payload.name || !payload.team || !payload.content) {
      setError("모든 항목을 입력해 주세요.");
      return;
    }

    setSubmitting(true);
    try {
      const editingExisting = editing && idRef.current;
      const res = await fetch(
        editingExisting ? `/api/pledges/${idRef.current}` : "/api/pledges",
        {
          method: editingExisting ? "PATCH" : "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload),
        }
      );
      const data = (await res.json()) as { pledge?: Pledge; error?: string };
      if (!res.ok || !data.pledge) {
        setError(data.error ?? "제출에 실패했습니다.");
        setSubmitting(false);
        return;
      }
      idRef.current = data.pledge.id;
      window.localStorage.setItem(STORAGE_KEY, data.pledge.id);
      setSubmitted(data.pledge);
      setEditing(false);
      setView("done");
    } catch {
      setError("네트워크 오류가 발생했습니다. 다시 시도해 주세요.");
    } finally {
      setSubmitting(false);
    }
  }

  function startEdit() {
    if (submitted) {
      setName(submitted.name);
      setTeam(submitted.team);
      setContent(submitted.content);
    }
    setEditing(true);
    setError(null);
    setView("form");
  }

  if (view === "loading") {
    return (
      <main className="flex min-h-[100dvh] items-center justify-center bg-slate-50 text-slate-400">
        <div className="animate-pulse text-lg">불러오는 중…</div>
      </main>
    );
  }

  if (view === "done" && submitted) {
    return (
      <main className="flex min-h-[100dvh] flex-col bg-slate-50 px-5 py-8">
        <div className="mx-auto flex w-full max-w-md flex-1 flex-col">
          <div className="mt-6 flex flex-col items-center text-center">
            <div className="flex h-16 w-16 items-center justify-center rounded-full bg-emerald-100">
              <svg viewBox="0 0 24 24" className="h-9 w-9 text-emerald-600" fill="none" stroke="currentColor" strokeWidth={2.5} strokeLinecap="round" strokeLinejoin="round">
                <path d="M5 13l4 4L19 7" />
              </svg>
            </div>
            <h1 className="mt-5 text-2xl font-bold text-slate-800">전송되었습니다</h1>
            <p className="mt-2 text-sm text-slate-500">소중한 다짐 감사합니다.</p>
          </div>

          <div className="mt-8 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
            <div className="flex items-baseline gap-2">
              <span className="text-lg font-bold text-slate-800">{submitted.name}</span>
              <span className="text-sm text-slate-500">{submitted.team}</span>
            </div>
            <p className="mt-3 whitespace-pre-wrap break-words text-[15px] leading-relaxed text-slate-700">
              {submitted.content}
            </p>
          </div>

          <div className="mt-auto pt-8">
            <button
              type="button"
              onClick={startEdit}
              className="w-full rounded-xl border border-slate-300 bg-white py-3.5 text-base font-semibold text-slate-700 active:scale-[0.99] active:bg-slate-100"
            >
              수정하기
            </button>
          </div>
        </div>
      </main>
    );
  }

  // form
  const contentLen = content.length;
  const overContent = contentLen > LIMITS.content;

  return (
    <main className="min-h-[100dvh] bg-slate-50 px-5 py-8">
      <form onSubmit={handleSubmit} className="mx-auto flex w-full max-w-md flex-col">
        <header className="mb-6">
          <h1 className="text-2xl font-bold text-slate-800">초심 다짐</h1>
          <p className="mt-1.5 text-sm text-slate-500">
            {editing ? "내용을 수정한 뒤 다시 전송해 주세요." : "오늘의 다짐을 남겨 주세요."}
          </p>
        </header>

        <label className="mb-4 block">
          <span className="mb-1.5 block text-sm font-semibold text-slate-700">이름</span>
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            maxLength={LIMITS.name}
            placeholder="이름"
            className="w-full rounded-xl border border-slate-300 bg-white px-4 py-3 text-base text-slate-800 outline-none focus:border-slate-800"
            autoComplete="off"
          />
        </label>

        <label className="mb-4 block">
          <span className="mb-1.5 block text-sm font-semibold text-slate-700">소속 센터</span>
          <input
            type="text"
            value={team}
            onChange={(e) => setTeam(e.target.value)}
            maxLength={LIMITS.team}
            placeholder="소속 센터"
            className="w-full rounded-xl border border-slate-300 bg-white px-4 py-3 text-base text-slate-800 outline-none focus:border-slate-800"
            autoComplete="off"
          />
        </label>

        <label className="mb-2 block">
          <span className="mb-1.5 block text-sm font-semibold text-slate-700">다짐</span>
          <textarea
            value={content}
            onChange={(e) => setContent(e.target.value)}
            maxLength={LIMITS.content}
            rows={7}
            placeholder="초심을 담은 다짐을 적어 주세요."
            className="w-full resize-none rounded-xl border border-slate-300 bg-white px-4 py-3 text-base leading-relaxed text-slate-800 outline-none focus:border-slate-800"
          />
        </label>
        <div className="mb-6 text-right text-xs tabular-nums text-slate-400">
          <span className={overContent ? "text-red-500" : ""}>{contentLen}</span> / {LIMITS.content}
        </div>

        {error && (
          <div className="mb-4 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-600">{error}</div>
        )}

        <button
          type="submit"
          disabled={submitting}
          className="w-full rounded-xl bg-slate-900 py-4 text-base font-bold text-white active:scale-[0.99] disabled:opacity-60"
        >
          {submitting ? "전송 중…" : editing ? "다시 전송" : "전송하기"}
        </button>

        {editing && (
          <button
            type="button"
            onClick={() => {
              setEditing(false);
              setError(null);
              setView("done");
            }}
            className="mt-3 w-full py-2 text-sm font-medium text-slate-500"
          >
            취소
          </button>
        )}
      </form>
    </main>
  );
}
