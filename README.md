# 초심 다짐 실시간 수집 웹앱

사내 교육에서 직원이 휴대폰으로 다짐을 제출하고, 강사가 프로젝터 화면에서 이름 카드를 클릭해 하나씩 공개하며 발표하는 실시간 수집·발표 도구입니다.

## 화면

| 경로 | 용도 | 기기 |
| --- | --- | --- |
| `/` | 직원 다짐 입력 | 휴대폰(세로) |
| `/admin` | 강사용 발표 화면 | 프로젝터(1920×1080) |
| `/qr` | QR 안내 화면 | 프로젝터 |

- **`/`** — 이름/다짐(300자, 실시간 글자수)을 입력해 제출합니다. 제출하면 완료 화면에서 본인 다짐을 다시 보여주고 **수정하기**로 재전송할 수 있습니다. `localStorage`에 제출 id를 저장해 재접속 시 완료 화면으로 바로 이동하며, 전송 중에는 버튼이 비활성화됩니다.
- **`/admin`** — 관리자 키 입력 후 진입합니다. 도착한 사람의 **이름·소속 카드**가 그리드로 쌓이고(3초 폴링, 등장 애니메이션), 카드를 클릭하면 전체화면 오버레이로 다짐이 크게 뜹니다. 다짐 본문은 길이에 따라 글자 크기가 자동 조절됩니다. 좌우 화살표·방향키로 이동하고 `ESC`로 닫습니다. 공개한 카드는 흐리게+체크 표시됩니다. 하단 툴바에 **랜덤 뽑기 / 전체 보기 / 전체화면 토글**이 있습니다. 카드에는 다짐 내용이 절대 표시되지 않습니다.
- **`/qr`** — 입력 페이지 주소의 QR을 크게 표시하고(서버에서 생성, 외부 API 미사용) 주소 텍스트와 현재 참여 인원수를 함께 보여줍니다.

## 기술 스택

- Next.js (App Router) + TypeScript
- Neon(Postgres, serverless) — `@neondatabase/serverless`
- Tailwind CSS (외부 UI 라이브러리 없음)
- 실시간 갱신은 3초 폴링(SSE 미사용)
- QR 코드는 `qrcode` 패키지로 서버 사이드 생성(외부 API 호출 없음)

## 환경변수

`.env.example`을 복사해 `.env.local`을 만들고 값을 채웁니다.

```bash
cp .env.example .env.local
```

| 변수 | 필수 | 설명 |
| --- | --- | --- |
| `DATABASE_URL` | ✅ | Neon Postgres 연결 문자열 |
| `ADMIN_KEY` | ✅ | `/admin` 진입 키 |
| `APP_URL` | ⬜ | QR이 가리킬 입력 페이지 주소. 미설정 시 요청 헤더에서 자동 추론 |

## 데이터베이스 초기화

> 앱은 첫 DB 접근 시 `pledges` 테이블을 자동 생성(`create table if not exists`)하므로 **아래 수동 실행은 선택 사항**입니다. `DATABASE_URL`만 올바르면 별도 초기화 없이 동작합니다. 스키마를 미리 만들어 두려면 다음을 실행하세요.

Neon 콘솔의 SQL Editor 또는 `psql`에서 `db/schema.sql`을 실행합니다.

```bash
psql "$DATABASE_URL" -f db/schema.sql
```

또는 Neon 웹 콘솔 → SQL Editor에 `db/schema.sql` 내용을 붙여넣고 실행합니다.

```sql
create table if not exists pledges (
  id text primary key,
  name text not null,
  team text not null,
  content text not null,
  revealed boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists idx_pledges_created on pledges(created_at);
```

## 로컬 실행

```bash
npm install
npm run dev
```

- 입력: http://localhost:3000/
- 강사용: http://localhost:3000/admin
- QR 안내: http://localhost:3000/qr

## Vercel 배포

1. 이 저장소를 Vercel 프로젝트로 가져옵니다.
2. **Settings → Environment Variables** 에 `DATABASE_URL`, `ADMIN_KEY`(필요 시 `APP_URL`)를 등록합니다.
3. 배포 후 Neon에서 `db/schema.sql`을 1회 실행합니다.

## API 개요

| 메서드 | 경로 | 인증 | 설명 |
| --- | --- | --- | --- |
| `POST` | `/api/pledges` | 공개 | 다짐 신규 제출 |
| `GET` | `/api/pledges` | 관리자 | 전체 다짐 목록(내용 포함) |
| `GET` | `/api/pledges/:id` | 공개 | 본인 제출 조회(id 소지자) |
| `PATCH` | `/api/pledges/:id` | 공개/관리자 | 본인 내용 수정 / 관리자 공개 상태 변경 |
| `GET` | `/api/stats` | 공개 | 제출 인원수 |
| `POST` | `/api/admin/verify` | 관리자 | 관리자 키 확인 |
| `GET` | `/api/health` | 공개 | 배포 진단(환경변수·DB 연결 상태) |

배포 후 문제가 있으면 브라우저에서 `/api/health` 로 접속해 `hasDatabaseUrl`·`db`·`hint` 값을 확인하세요.

관리자 인증은 요청 헤더 `x-admin-key` 값을 `ADMIN_KEY`와 비교합니다.
