import Icon from './Icon.jsx';

const POINTS = [
  ['queue', '대기 순번대로 공정하게'],
  ['chain', '모든 티켓을 블록체인에 기록'],
  ['shield', '위조 티켓은 게이트에서 차단'],
];

/** 로그인 · 회원가입 화면의 틀 — 왼쪽은 소개, 오른쪽은 입력 폼 */
export default function AuthLayout({ title, lead, children }) {
  return (
    <div className="wrap auth-page">
      <div className="auth-card">
        <div className="auth-brand">
          <span className="logo-text">Fair<b>Ticket</b></span>
          <h2>블록체인으로 지키는<br />공정한 예매</h2>
          <ul>
            {POINTS.map(([icon, text]) => <li key={text}><Icon name={icon} size={19} /> {text}</li>)}
          </ul>
        </div>
        <div className="auth-form">
          <h1>{title}</h1>
          <p className="muted">{lead}</p>
          {children}
        </div>
      </div>
    </div>
  );
}
