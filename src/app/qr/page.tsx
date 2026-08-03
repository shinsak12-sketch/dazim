import { headers } from "next/headers";
import QRCode from "qrcode";
import { sql } from "@/lib/db";
import LiveCount from "@/components/LiveCount";

export const dynamic = "force-dynamic";

function resolveAppUrl(): string {
  // 우선순위: APP_URL 환경변수 → 요청 헤더 기반 추론
  const fromEnv = process.env.APP_URL?.trim();
  if (fromEnv) return fromEnv.replace(/\/$/, "") + "/";

  const h = headers();
  const host = h.get("x-forwarded-host") ?? h.get("host") ?? "localhost:3000";
  const proto = h.get("x-forwarded-proto") ?? (host.startsWith("localhost") ? "http" : "https");
  return `${proto}://${host}/`;
}

async function getCount(): Promise<number> {
  try {
    const rows = (await sql`select count(*)::int as count from pledges`) as { count: number }[];
    return rows[0]?.count ?? 0;
  } catch {
    return 0;
  }
}

export default async function QrPage() {
  const url = resolveAppUrl();
  const [svg, count] = await Promise.all([
    QRCode.toString(url, {
      type: "svg",
      margin: 1,
      width: 560,
      errorCorrectionLevel: "M",
      color: { dark: "#0f172a", light: "#ffffff" },
    }),
    getCount(),
  ]);

  return (
    <main className="flex h-[100dvh] flex-col items-center justify-center bg-white px-8 text-slate-900">
      <h1 className="mb-3 text-6xl font-black tracking-tight">초심 다짐</h1>
      <p className="mb-10 text-3xl text-slate-500">
        휴대폰으로 QR을 스캔해 다짐을 남겨 주세요
      </p>

      <div
        className="rounded-3xl border-4 border-slate-900 bg-white p-6 shadow-xl"
        // qrcode가 생성한 안전한 SVG 마크업
        dangerouslySetInnerHTML={{ __html: svg }}
      />

      <p className="mt-8 break-all text-2xl font-semibold text-slate-700">{url}</p>

      <div className="mt-10 rounded-full bg-slate-900 px-10 py-4 text-3xl font-bold text-white">
        지금까지 <LiveCount initial={count} />명 참여
      </div>
    </main>
  );
}
