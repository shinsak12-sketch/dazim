import { redirect } from "next/navigation";
import { accountNames } from "@/lib/auth";
import { currentUser } from "@/lib/session";
import LoginForm from "./LoginForm";

export const dynamic = "force-dynamic";

export default async function LoginPage() {
  if (await currentUser()) redirect("/");
  return <LoginForm accounts={accountNames()} />;
}
