import { useState } from 'react';
import { post } from '../api.js';

/**
 * 운영자 · 게이트 스태프 로그인 폼 (회원 로그인은 Login 화면이 따로).
 * path로 POST {loginId, password} → 성공하면 onDone(응답)
 */
export default function LoginForm({ title, path, defaultId = '', hint, onDone, children }) {
  const [id, setId] = useState(defaultId);
  const [pw, setPw] = useState('');
  const [error, setError] = useState('');

  const submit = async (e) => {
    e.preventDefault();
    try { onDone(await post(path, { loginId: id, password: pw })); setError(''); }
    catch (err) { setError(err.message); }
  };

  return (
    <div className="container narrow">
      <div className="card auth">
        <h1>{title}</h1>
        <form className="form" onSubmit={submit}>
          <input value={id} onChange={(e) => setId(e.target.value)} placeholder="아이디" required />
          <input value={pw} onChange={(e) => setPw(e.target.value)} type="password" placeholder="비밀번호" required autoFocus />
          {error && <div className="error">{error}</div>}
          <button className="btn big full">로그인</button>
        </form>
        {hint && <p className="muted small">{hint} {children}</p>}
      </div>
    </div>
  );
}
