import { useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { get } from '../api.js';
import { useAuth } from '../auth.jsx';
import AuthLayout from '../components/AuthLayout.jsx';

export default function Login() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = params.get('next') || '/';
  const { me, login, toast } = useAuth();
  const [loginId, setLoginId] = useState('');
  const [password, setPassword] = useState('');
  const [demo, setDemo] = useState([]);
  const [error, setError] = useState('');

  useEffect(() => { if (me) navigate(next, { replace: true }); }, [me, navigate, next]);
  useEffect(() => { get('/api/members/demo').then(setDemo).catch(() => {}); }, []);

  const submit = async (id, pw) => {
    setError('');
    try {
      const m = await login(id, pw);
      toast(`${m.name} 님, 환영합니다`);
      navigate(next, { replace: true });
    } catch (e) { setError(e.message); }
  };

  return (
    <AuthLayout title="로그인" lead="예매 내역과 취소표 알림을 확인하세요.">
      <form onSubmit={(e) => { e.preventDefault(); submit(loginId, password); }} className="form">
        <label>아이디<input value={loginId} onChange={(e) => setLoginId(e.target.value)} placeholder="아이디" autoFocus required /></label>
        <label>비밀번호<input value={password} onChange={(e) => setPassword(e.target.value)} type="password" placeholder="비밀번호" required /></label>
        {error && <div className="error">{error}</div>}
        <button className="btn big full">로그인</button>
      </form>
      <p className="auth-switch">아직 회원이 아니신가요? <Link to="/signup" className="textlink">회원가입</Link></p>
      {demo.length > 0 && (
        <div className="demo-accounts">
          <span>시연 계정으로 바로 로그인</span>
          <div className="demo-list">
            {demo.map((d) => (
              <button key={d.loginId} onClick={() => submit(d.loginId, d.password)}>
                <i>{d.name.slice(0, 1)}</i>{d.name}
              </button>
            ))}
          </div>
        </div>
      )}
    </AuthLayout>
  );
}
