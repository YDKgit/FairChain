import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { post } from '../api.js';
import { useAuth } from '../auth.jsx';
import AuthLayout from '../components/AuthLayout.jsx';

export default function Signup() {
  const navigate = useNavigate();
  const { login, toast } = useAuth();
  const [form, setForm] = useState({ loginId: '', password: '', name: '', contact: '' });
  const [error, setError] = useState('');
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  const submit = async (e) => {
    e.preventDefault();
    setError('');
    try {
      await post('/api/members', form);
      await login(form.loginId, form.password);
      toast('가입을 환영합니다');
      navigate('/');
    } catch (err) { setError(err.message); }
  };

  return (
    <AuthLayout title="회원가입" lead="가입하면 바로 예매할 수 있습니다.">
      <form onSubmit={submit} className="form">
        <label>아이디<input value={form.loginId} onChange={set('loginId')} placeholder="아이디" required autoFocus /></label>
        <label>비밀번호<input value={form.password} onChange={set('password')} type="password" placeholder="비밀번호" required /></label>
        <label>이름<input value={form.name} onChange={set('name')} placeholder="이름" required /></label>
        <label>연락처 (선택)<input value={form.contact} onChange={set('contact')} placeholder="010-0000-0000" /></label>
        {error && <div className="error">{error}</div>}
        <button className="btn big full">가입하기</button>
      </form>
      <p className="auth-switch">이미 회원이신가요? <Link to="/login" className="textlink">로그인</Link></p>
    </AuthLayout>
  );
}
