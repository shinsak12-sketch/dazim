"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import QRCode from "qrcode";
import type { AdminPledge } from "@/lib/types";
import FitText from "@/components/FitText";
import "./admin.css";

const KEY_STORAGE = "dazim.adminKey";
const THEME_STORAGE = "dazim.theme";
const POLL_MS = 3000;
const ACCENTS = ["green", "indigo", "teal", "amber", "coral"] as const;

type Theme = "light" | "dark";

export default function AdminPage() {
  const [adminKey, setAdminKey] = useState<string | null>(null);
  const [checking, setChecking] = useState(true);
  const [theme, setTheme] = useState<Theme>("light");

  // Pretendard 웹폰트 비차단 로드(사내망에서 막혀도 시스템 폰트로 자연 폴백)
  useEffect(() => {
    const href =
      "https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/variable/pretendardvariable-dynamic-subset.min.css";
    if (document.querySelector(`link[href="${href}"]`)) return;
    const link = document.createElement("link");
    link.rel = "stylesheet";
    link.href = href;
    link.crossOrigin = "anonymous";
    document.head.appendChild(link);
  }, []);

  // 테마 복원
  useEffect(() => {
    const stored = window.localStorage.getItem(THEME_STORAGE) as Theme | null;
    if (stored === "light" || stored === "dark") setTheme(stored);
  }, []);

  const toggleTheme = useCallback(() => {
    setTheme((t) => {
      const next = t === "dark" ? "light" : "dark";
      window.localStorage.setItem(THEME_STORAGE, next);
      return next;
    });
  }, []);

  // 저장된 키 검증
  useEffect(() => {
    const stored = window.localStorage.getItem(KEY_STORAGE);
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

  return (
    <div className="deck" data-theme={theme}>
      {checking ? (
        <div className="placeholder" style={{ flex: 1 }}>
          <div className="sm">불러오는 중…</div>
        </div>
      ) : !adminKey ? (
        <KeyGate onSuccess={(k) => setAdminKey(k)} theme={theme} toggleTheme={toggleTheme} />
      ) : (
        <Board
          adminKey={adminKey}
          theme={theme}
          toggleTheme={toggleTheme}
          onSignOut={() => {
            window.localStorage.removeItem(KEY_STORAGE);
            setAdminKey(null);
          }}
        />
      )}
    </div>
  );
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

function KeyGate({
  onSuccess,
  theme,
  toggleTheme,
}: {
  onSuccess: (key: string) => void;
  theme: Theme;
  toggleTheme: () => void;
}) {
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
    <>
      <div style={{ position: "absolute", top: 16, right: 16, zIndex: 3 }}>
        <button className="btn sm" onClick={toggleTheme} title="라이트/다크 전환" aria-label="테마 전환">
          {theme === "dark" ? "☀︎" : "☾"}
        </button>
      </div>
      <form className="gate" onSubmit={submit}>
        <span className="eyebrow">
          <span className="dot" /> 진행자 화면
        </span>
        <h1 style={{ marginTop: "0.5em" }}>초심 다짐</h1>
        <p>관리자 키를 입력해 주세요.</p>
        <input
          type="password"
          className="field"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          placeholder="ADMIN KEY"
          autoFocus
        />
        {error && <div className="err">{error}</div>}
        <button type="submit" className="btn primary submit" disabled={busy}>
          {busy ? "확인 중…" : "입장"}
        </button>
      </form>
    </>
  );
}

/* ---------------- 발표 보드 ---------------- */

function Board({
  adminKey,
  theme,
  toggleTheme,
  onSignOut,
}: {
  adminKey: string;
  theme: Theme;
  toggleTheme: () => void;
  onSignOut: () => void;
}) {
  const [pledges, setPledges] = useState<AdminPledge[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [overlayIndex, setOverlayIndex] = useState<number | null>(null);
  const [showAll, setShowAll] = useState(false);
  const [showQR, setShowQR] = useState(false);
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
      /* 폴링 중 일시 오류 무시 */
    }
  }, [adminKey]);

  useEffect(() => {
    load();
    const t = setInterval(load, POLL_MS);
    return () => clearInterval(t);
  }, [load]);

  useEffect(() => {
    const onFsChange = () => setIsFullscreen(!!document.fullscreenElement);
    document.addEventListener("fullscreenchange", onFsChange);
    return () => document.removeEventListener("fullscreenchange", onFsChange);
  }, []);

  const reveal = useCallback(
    async (id: string) => {
      setPledges((prev) => prev.map((p) => (p.id === id ? { ...p, revealed: true } : p)));
      try {
        await fetch(`/api/pledges/${id}`, {
          method: "PATCH",
          headers: { "Content-Type": "application/json", "x-admin-key": adminKey },
          body: JSON.stringify({ revealed: true }),
        });
      } catch {
        /* 다음 폴링에서 재동기화 */
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
  const allRevealed = pledges.length > 0 && revealedCount === pledges.length;

  const pickRandom = useCallback(() => {
    const list = pledgesRef.current;
    const candidates = list.map((p, i) => ({ p, i })).filter(({ p }) => !p.revealed);
    if (candidates.length === 0) return;
    openAt(candidates[getRandomInt(candidates.length)].i);
  }, [openAt]);

  async function toggleFullscreen() {
    try {
      if (!document.fullscreenElement) await document.documentElement.requestFullscreen();
      else await document.exitFullscreen();
    } catch {
      /* 미지원 무시 */
    }
  }

  return (
    <>
      {/* 상단 바 */}
      <header className="topbar">
        <div>
          <span className="eyebrow">
            <span className="dot" /> 진행자 화면 · 초심 다짐
          </span>
          <h1 className="headline" style={{ marginTop: "0.35em" }}>
            오늘의 다짐
          </h1>
        </div>
        <div className="count">
          <div className="count-box">
            <div className="count-num">
              {pledges.length}
              <span className="u">명 도착</span>
            </div>
            <div className="count-sub">
              공개 {revealedCount} / {pledges.length}
            </div>
          </div>
          <button className="btn sm" onClick={toggleTheme} title="라이트/다크 전환" aria-label="테마 전환">
            {theme === "dark" ? "☀︎" : "☾"}
          </button>
          <button className="btn sm" onClick={onSignOut} title="관리자 키 재입력">
            나가기
          </button>
        </div>
      </header>

      {/* 카드 그리드 */}
      <section className="board">
        {!loaded ? (
          <div className="placeholder">
            <div className="sm">불러오는 중…</div>
          </div>
        ) : pledges.length === 0 ? (
          <div className="placeholder">
            <div className="big">아직 도착한 다짐이 없습니다</div>
            <div className="sm">QR을 스캔해 다짐을 남겨 주세요.</div>
          </div>
        ) : (
          <div className="grid">
            {pledges.map((p, i) => (
              <button
                key={p.id}
                className={p.revealed ? "name-card revealed" : "name-card"}
                onClick={() => openAt(i)}
              >
                {p.revealed && (
                  <span className="check">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={3} strokeLinecap="round" strokeLinejoin="round">
                      <path d="M5 13l4 4L19 7" />
                    </svg>
                  </span>
                )}
                <span className="nm">{p.name}</span>
                {p.team && <span className="tm">{p.team}</span>}
              </button>
            ))}
          </div>
        )}
      </section>

      {/* 하단 툴바 */}
      <footer className="toolbar">
        <button className="btn lg" onClick={() => setShowQR(true)}>
          📱 QR 코드
        </button>
        <button className="btn primary lg" onClick={pickRandom} disabled={allRevealed}>
          🎲 랜덤 뽑기
        </button>
        <button className="btn lg" onClick={() => setShowAll(true)} disabled={pledges.length === 0}>
          전체 보기
        </button>
        <button className="btn lg" onClick={toggleFullscreen}>
          {isFullscreen ? "전체화면 해제" : "전체화면"}
        </button>
      </footer>

      {overlayIndex !== null && pledges[overlayIndex] && (
        <PledgeOverlay
          pledges={pledges}
          index={overlayIndex}
          onNavigate={openAt}
          onClose={() => setOverlayIndex(null)}
        />
      )}

      {showAll && <AllView pledges={pledges} onClose={() => setShowAll(false)} />}

      {showQR && <QrView count={pledges.length} onClose={() => setShowQR(false)} />}
    </>
  );
}

/* ---------------- QR 안내 오버레이 ---------------- */

function QrView({ count, onClose }: { count: number; onClose: () => void }) {
  const [svg, setSvg] = useState<string>("");
  const [url, setUrl] = useState<string>("");

  useEffect(() => {
    // 배포된 입력 페이지(현재 사이트의 루트) 주소를 QR로 만든다. 외부 API 호출 없음.
    const u = window.location.origin + "/";
    setUrl(u);
    QRCode.toString(u, {
      type: "svg",
      margin: 1,
      width: 500,
      errorCorrectionLevel: "M",
      color: { dark: "#0f172a", light: "#ffffff" },
    })
      .then(setSvg)
      .catch(() => setSvg(""));
  }, []);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="deck-qr">
      <button className="btn sm qr-close" onClick={onClose} aria-label="닫기">
        ✕ 닫기
      </button>

      <span className="eyebrow qr-eyebrow">
        <span className="dot" /> 참여 안내
      </span>
      <h2 className="qr-title">
        휴대폰으로 QR을 스캔해
        <br />
        다짐을 남겨 주세요
      </h2>

      <div className="qr-card">
        {svg ? (
          <div dangerouslySetInnerHTML={{ __html: svg }} />
        ) : (
          <div className="qr-loading">QR 생성 중…</div>
        )}
      </div>

      <div className="qr-url">{url}</div>

      <div className="qr-count">
        지금까지 <span className="n">{count}</span>명 참여
      </div>
    </div>
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
  const accent = ACCENTS[index % ACCENTS.length];

  return (
    <div className="deck-overlay" data-accent={accent}>
      <div className="ov-head">
        <div>
          <div className="ov-name">{p.name}</div>
          {p.team && <div className="ov-team">{p.team}</div>}
        </div>
        <button className="btn sm" onClick={onClose} aria-label="닫기">
          ✕ 닫기
        </button>
      </div>

      <div className="ov-body">
        <button className="navbtn" onClick={() => hasPrev && onNavigate(index - 1)} disabled={!hasPrev} aria-label="이전">
          ‹
        </button>
        <div style={{ position: "relative", flex: 1, alignSelf: "stretch", minWidth: 0 }}>
          <FitText key={p.id} text={p.content} min={24} max={150} className="ov-quote" />
        </div>
        <button className="navbtn" onClick={() => hasNext && onNavigate(index + 1)} disabled={!hasNext} aria-label="다음">
          ›
        </button>
      </div>

      <div className="ov-pos">
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
    <div className="deck-all">
      <div className="all-head">
        <h2 className="headline">전체 다짐 ({pledges.length})</h2>
        <button className="btn sm" onClick={onClose} aria-label="닫기">
          ✕ 닫기
        </button>
      </div>
      <div className="all-body">
        <div className="all-grid">
          {pledges.map((p) => (
            <div key={p.id} className="pcard">
              <div className="ph">
                <span className="pn">{p.name}</span>
                {p.team && <span className="pt">{p.team}</span>}
                {p.revealed && <span className="pr">· 공개됨</span>}
              </div>
              <p className="pc">{p.content}</p>
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
  return 0;
}
