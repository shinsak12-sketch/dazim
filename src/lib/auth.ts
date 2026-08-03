import { NextRequest } from "next/server";

export const ADMIN_HEADER = "x-admin-key";

export function isAdmin(req: NextRequest): boolean {
  const key = process.env.ADMIN_KEY;
  if (!key) return false;
  const provided = req.headers.get(ADMIN_HEADER);
  return provided === key;
}
