// 클라이언트/서버 공용 타입

// 직원 본인에게 반환되는 다짐(내용 포함)
export type Pledge = {
  id: string;
  name: string;
  team: string;
  content: string;
  revealed: boolean;
  created_at: string;
  updated_at: string;
};

// 강사용 화면에서 사용하는 다짐 목록 항목(내용 포함)
export type AdminPledge = Pledge;
