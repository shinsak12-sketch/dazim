export default function SetupNotice({ message }: { message: string }) {
  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-950 p-6 text-slate-100">
      <div className="w-full max-w-sm rounded-2xl bg-slate-900 p-5 ring-1 ring-slate-800">
        <h1 className="text-lg font-bold">데이터베이스 연결 오류</h1>
        <p className="mt-2 break-words text-sm leading-relaxed text-rose-300">{message}</p>
        <p className="mt-4 text-sm leading-relaxed text-slate-400">
          Vercel 환경변수를 확인해 주세요. <code className="rounded bg-slate-800 px-1">/api/health</code> 를 열면
          무엇이 빠졌는지 알려줍니다.
        </p>
      </div>
    </main>
  );
}
