"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

export default function LoginForm({ accounts }: { accounts: string[] }) {
  const router = useRouter();
  const [name, setName] = useState<string | null>(accounts.length === 1 ? accounts[0] : null);
  const [pin, setPin] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!name || busy) return;
    setBusy(true);
    setError(null);
    try {
      const res = await fetch("/api/login", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ name, pin }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        setError(data?.error ?? "로그인에 실패했습니다.");
        setPin("");
        return;
      }
      router.replace("/");
      router.refresh();
    } catch {
      setError("네트워크 오류가 발생했습니다.");
    } finally {
      setBusy(false);
    }
  }

  if (accounts.length === 0) {
    return (
      <main className="flex min-h-screen items-center justify-center bg-slate-950 p-6 text-slate-100">
        <div className="w-full max-w-sm rounded-2xl bg-slate-900 p-5 ring-1 ring-slate-800">
          <h1 className="text-lg font-bold">설정이 필요합니다</h1>
          <p className="mt-2 text-sm leading-relaxed text-slate-400">
            Vercel 환경변수에 <code className="rounded bg-slate-800 px-1">ACCOUNTS</code> 를 넣어주세요.
          </p>
          <pre className="mt-3 overflow-x-auto rounded-lg bg-slate-950 p-3 text-xs text-emerald-300">
ACCOUNTS=남편:1234,아내:5678</pre>
          <p className="mt-3 text-xs text-slate-500">
            <code className="rounded bg-slate-800 px-1">AUTH_SECRET</code> (16자 이상 랜덤 문자열)과{" "}
            <code className="rounded bg-slate-800 px-1">DATABASE_URL</code> 도 함께 필요합니다.
          </p>
        </div>
      </main>
    );
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-950 p-6 text-slate-100">
      <form onSubmit={submit} className="w-full max-w-sm">
        <h1 className="text-center text-2xl font-bold tracking-tight">누구세요?</h1>

        <div className="mt-6 grid grid-cols-2 gap-3">
          {accounts.map((a) => (
            <button
              key={a}
              type="button"
              onClick={() => {
                setName(a);
                setError(null);
              }}
              className={`rounded-2xl py-5 text-base font-bold transition active:scale-95 ${
                name === a
                  ? "bg-sky-500 text-slate-950"
                  : "bg-slate-900 text-slate-200 ring-1 ring-slate-800"
              }`}
            >
              {a}
            </button>
          ))}
        </div>

        <label htmlFor="pin" className="mt-6 block text-xs font-semibold uppercase tracking-wide text-slate-400">
          PIN
        </label>
        <input
          id="pin"
          type="password"
          inputMode="numeric"
          autoComplete="current-password"
          value={pin}
          onChange={(e) => setPin(e.target.value)}
          disabled={!name}
          className="mt-2 w-full rounded-xl bg-slate-900 px-4 py-4 text-center text-2xl tracking-[0.4em] text-slate-100 outline-none ring-1 ring-slate-800 focus:ring-2 focus:ring-sky-500 disabled:opacity-40"
        />

        {error && <p className="mt-3 text-center text-sm text-rose-400">{error}</p>}

        <button
          type="submit"
          disabled={!name || !pin || busy}
          className="mt-5 w-full rounded-xl bg-sky-500 py-4 text-base font-bold text-slate-950 transition active:scale-[0.99] disabled:opacity-40"
        >
          {busy ? "확인 중…" : "들어가기"}
        </button>
      </form>
    </main>
  );
}
