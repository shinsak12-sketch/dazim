import { getDomain, listComics } from "@/lib/db";
import { currentProfile, listProfiles } from "@/lib/profile";
import { buildHost } from "@/lib/site";
import ComicList from "./ComicList";
import SetupNotice from "./SetupNotice";

export const dynamic = "force-dynamic";

export default async function Home() {
  const user = currentProfile();

  try {
    const [domain, comics] = await Promise.all([getDomain(), listComics(user)]);
    return (
      <ComicList
        user={user}
        profiles={listProfiles()}
        initialDomain={{ ...domain, host: buildHost(domain) }}
        initialComics={comics}
      />
    );
  } catch (e) {
    return <SetupNotice message={e instanceof Error ? e.message : "데이터베이스에 연결하지 못했습니다."} />;
  }
}
