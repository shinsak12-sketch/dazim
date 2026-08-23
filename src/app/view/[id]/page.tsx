import { notFound, redirect } from "next/navigation";
import { getDomain, listComics } from "@/lib/db";
import { currentUser } from "@/lib/session";
import { buildHost, buildUrl } from "@/lib/site";
import Viewer from "./Viewer";

export const dynamic = "force-dynamic";

export default async function ViewPage({ params }: { params: { id: string } }) {
  const user = await currentUser();
  if (!user) redirect("/login");

  const [domain, comics] = await Promise.all([getDomain(), listComics(user)]);
  const comic = comics.find((c) => c.id === params.id);
  if (!comic) notFound();

  return (
    <Viewer
      comic={comic}
      host={buildHost(domain)}
      url={buildUrl(domain, comic.path)}
      domainNum={domain.num}
    />
  );
}
