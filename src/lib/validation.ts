export const LIMITS = {
  name: 20,
  team: 30,
  content: 300,
} as const;

export type PledgeInput = {
  name: string;
  team: string;
  content: string;
};

export type ValidationResult =
  | { ok: true; value: PledgeInput }
  | { ok: false; error: string };

export function validatePledge(body: unknown): ValidationResult {
  if (typeof body !== "object" || body === null) {
    return { ok: false, error: "잘못된 요청입니다." };
  }
  const b = body as Record<string, unknown>;

  const name = typeof b.name === "string" ? b.name.trim() : "";
  const team = typeof b.team === "string" ? b.team.trim() : "";
  const content = typeof b.content === "string" ? b.content.trim() : "";

  if (!name) return { ok: false, error: "이름을 입력해 주세요." };
  if (name.length > LIMITS.name)
    return { ok: false, error: `이름은 ${LIMITS.name}자 이내로 입력해 주세요.` };

  // 소속 센터는 선택 항목(미입력 시 빈 값으로 저장).
  if (team.length > LIMITS.team)
    return { ok: false, error: `소속은 ${LIMITS.team}자 이내로 입력해 주세요.` };

  if (!content) return { ok: false, error: "다짐을 입력해 주세요." };
  if (content.length > LIMITS.content)
    return { ok: false, error: `다짐은 ${LIMITS.content}자 이내로 입력해 주세요.` };

  return { ok: true, value: { name, team, content } };
}
