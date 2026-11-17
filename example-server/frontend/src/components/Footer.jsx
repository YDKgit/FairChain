import { Link } from 'react-router-dom';
import { useAuth } from '../auth.jsx';
import { GENRES } from '../catalog.js';

export default function Footer() {
  const { config } = useAuth();
  return (
    <footer className="footer">
      <div className="wrap footer-grid">
        <div>
          <div className="footer-brand">Fair<b>Ticket</b></div>
          <p>
            FairChain 플랫폼 위에서 동작하는 예제 예매 사이트입니다. 좌석의 발행 · 판매 · 취소 · 입장 기록은
            해시값으로 FairChain 블록체인 노드에 남고, 입장할 때 그 기록과 대조합니다.
          </p>
        </div>
        <div>
          <h4>공연</h4>
          <ul>
            {GENRES.slice(0, 5).map((g) => <li key={g.key}><Link to={`/concerts?genre=${g.key}`}>{g.label}</Link></li>)}
          </ul>
        </div>
        <div>
          <h4>내 예매</h4>
          <ul>
            <li><Link to="/tickets">내 티켓</Link></li>
            <li><Link to="/reallocation">취소표 알림</Link></li>
          </ul>
        </div>
        <div>
          <h4>FairChain</h4>
          <ul>
            <li>대기열 · 매크로 차단</li>
            <li>취소표 재배정 명단</li>
            <li>블록 기록 · 검증</li>
            {config.fairChainUrl && (
              <li><a href={`${config.fairChainUrl}/dashboard.html`} target="_blank" rel="noreferrer">관제 대시보드</a></li>
            )}
          </ul>
        </div>
      </div>
      <div className="wrap footer-base">졸업 프로젝트 시연용 사이트입니다. 실제 결제와 실제 공연은 없습니다.</div>
    </footer>
  );
}
