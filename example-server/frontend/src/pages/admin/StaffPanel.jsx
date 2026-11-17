import { useState } from 'react';
import { post } from '../../api.js';

const EMPTY = { loginId: '', password: '', name: '' };

/** 게이트 스태프 계정 등록 (운영자만) */
export default function StaffPanel({ toast }) {
  const [form, setForm] = useState(EMPTY);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });
  const submit = async (e) => {
    e.preventDefault();
    try { await post('/api/operator/staff', form); toast('스태프 계정을 만들었습니다'); setForm(EMPTY); }
    catch (err) { toast(err.message, 'error'); }
  };
  return (
    <div className="card" style={{ maxWidth: 480 }}>
      <h3>게이트 스태프 계정 등록</h3>
      <p className="muted small">게이트 화면(/gate)에서 이 계정으로 로그인해 QR을 찍습니다.</p>
      <form className="form" onSubmit={submit}>
        <label>아이디<input value={form.loginId} onChange={set('loginId')} required /></label>
        <label>비밀번호<input type="password" value={form.password} onChange={set('password')} required /></label>
        <label>이름<input value={form.name} onChange={set('name')} required /></label>
        <button className="btn">등록</button>
      </form>
    </div>
  );
}
