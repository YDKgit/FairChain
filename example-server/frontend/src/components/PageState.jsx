/** 화면 공통 상태 — 오류 · 불러오는 중 · 빈 목록 */

export function ErrorBox({ message }) {
  return <div className="container"><div className="card error">{message}</div></div>;
}

export function Loading({ text = '불러오는 중…' }) {
  return <div className="container"><div className="empty">{text}</div></div>;
}
