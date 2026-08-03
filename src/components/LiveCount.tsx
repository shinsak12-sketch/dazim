"use client";

import { useEffect, useState } from "react";

// QR 안내 화면의 현재 제출 인원수를 3초 간격 폴링으로 갱신한다.
export default function LiveCount({ initial }: { initial: number }) {
  const [count, setCount] = useState(initial);

  useEffect(() => {
    let alive = true;
    async function load() {
      try {
        const res = await fetch("/api/stats", { cache: "no-store" });
        if (!res.ok) return;
        const data = (await res.json()) as { count: number };
        if (alive) setCount(data.count);
      } catch {
        /* 무시 */
      }
    }
    load();
    const t = setInterval(load, 3000);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, []);

  return <span className="tabular-nums">{count}</span>;
}
