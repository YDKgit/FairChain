// 내 정보(로그인 상태 · 서버 주소 · 게이트 진입), 로그인, 회원가입
import { useState } from 'react';
import { KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { get, post, saveServerUrl, serverUrl } from '../api';
import { useApp } from '../app';
import { Button, Card, ErrorBox } from '../components/ui';
import { C } from '../theme';

const DEMO = [['chulsoo', '김철수'], ['younghee', '이영희'], ['minsu', '박민수'], ['jiwoo', '최지우'], ['haneul', '정하늘']];

export function Field({ label, ...props }) {
  return (
    <View style={{ gap: 6 }}>
      <Text style={st.label}>{label}</Text>
      <TextInput style={st.input} placeholderTextColor={C.faint} autoCapitalize="none" autoCorrect={false} {...props} />
    </View>
  );
}

export function Me() {
  const { member, logout, nav, refresh, toast } = useApp();
  const [url, setUrl] = useState(serverUrl());
  const [checking, setChecking] = useState(false);
  const [result, setResult] = useState(null);

  const apply = async () => {
    setChecking(true); setResult(null);
    await saveServerUrl(url);
    setUrl(serverUrl());
    try { await get('/api/config'); setResult({ ok: true, text: '연결되었습니다' }); }
    catch (e) { setResult({ ok: false, text: e.message }); }
    await refresh();
    setChecking(false);
  };

  return (
    <ScrollView contentContainerStyle={st.page} keyboardShouldPersistTaps="handled">
      <Card style={{ gap: 12 }}>
        {member ? (
          <>
            <Text style={st.name}>{member.name} 님</Text>
            <Text style={st.muted}>아이디 {member.loginId}</Text>
            <Button title="로그아웃" kind="ghost" onPress={async () => { await logout(); toast('로그아웃했습니다'); }} />
          </>
        ) : (
          <>
            <Text style={st.name}>로그인이 필요합니다</Text>
            <Text style={st.muted}>예매 · 내 티켓 · 취소표 알림은 로그인 후 쓸 수 있습니다.</Text>
            <Button title="로그인" onPress={() => nav.push('login', {})} />
            <Button title="회원가입" kind="ghost" onPress={() => nav.push('signup', {})} />
          </>
        )}
      </Card>

      <Card style={{ gap: 12 }}>
        <Text style={st.h2}>입장 게이트 (스태프)</Text>
        <Text style={st.muted}>관객의 입장 QR을 휴대폰 카메라로 찍어 블록체인 기록과 대조합니다.</Text>
        <Button title="게이트 열기" kind="ghost" onPress={() => nav.push('gate', {})} />
      </Card>

      <Card style={{ gap: 12 }}>
        <Text style={st.h2}>서버 주소</Text>
        <Text style={st.muted}>예제서버를 띄운 컴퓨터의 주소입니다. 휴대폰과 컴퓨터가 같은 와이파이에 있어야 합니다.</Text>
        <Field label="예제서버" value={url} onChangeText={setUrl} placeholder="http://192.168.0.10:8080" keyboardType="url" />
        <Button title="저장하고 연결 확인" kind="ghost" busy={checking} onPress={apply} />
        {result && <Text style={{ color: result.ok ? C.ok : C.bad, fontSize: 13, lineHeight: 19 }}>{result.text}</Text>}
      </Card>
      <Text style={[st.muted, { textAlign: 'center' }]}>FairTicket — FairChain 예제 예매 앱</Text>
    </ScrollView>
  );
}

export function Login({ then }) {
  const { login, nav, toast } = useApp();
  const [id, setId] = useState('');
  const [pw, setPw] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const submit = async (loginId = id, password = pw) => {
    if (!loginId || !password) { setError('아이디와 비밀번호를 입력하세요'); return; }
    setBusy(true); setError('');
    try {
      const m = await login(loginId.trim(), password);
      toast(`${m.name} 님, 환영합니다`);
      if (then?.name) nav.replace(then.name, then.params || {});
      else nav.back();
    } catch (e) { setError(e.message); }
    setBusy(false);
  };

  return (
    <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <ScrollView contentContainerStyle={st.page} keyboardShouldPersistTaps="handled">
        <Text style={st.title}>로그인</Text>
        <Card style={{ gap: 12 }}>
          <Field label="아이디" value={id} onChangeText={setId} placeholder="아이디" />
          <Field label="비밀번호" value={pw} onChangeText={setPw} placeholder="비밀번호" secureTextEntry onSubmitEditing={() => submit()} />
          {error ? <ErrorBox message={error} /> : null}
          <Button title="로그인" busy={busy} onPress={() => submit()} />
          <Pressable onPress={() => nav.replace('signup', { then })}><Text style={st.link}>처음이신가요? 회원가입</Text></Pressable>
        </Card>
        <Card style={{ gap: 10 }}>
          <Text style={st.h2}>시연 계정으로 바로 로그인</Text>
          <View style={st.demoRow}>
            {DEMO.map(([loginId, name]) => (
              <Pressable key={loginId} style={st.demo} onPress={() => submit(loginId, 'demo1234')}>
                <Text style={st.demoText}>{name}</Text>
              </Pressable>
            ))}
          </View>
        </Card>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

export function Signup({ then }) {
  const { login, nav, toast } = useApp();
  const [form, setForm] = useState({ loginId: '', password: '', name: '', contact: '' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const set = (k) => (v) => setForm((f) => ({ ...f, [k]: v }));

  const submit = async () => {
    setBusy(true); setError('');
    try {
      await post('/api/members', form);
      await login(form.loginId, form.password);
      toast('가입되었습니다');
      if (then?.name) nav.replace(then.name, then.params || {});
      else nav.back();
    } catch (e) { setError(e.message); }
    setBusy(false);
  };

  return (
    <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <ScrollView contentContainerStyle={st.page} keyboardShouldPersistTaps="handled">
        <Text style={st.title}>회원가입</Text>
        <Card style={{ gap: 12 }}>
          <Field label="아이디" value={form.loginId} onChangeText={set('loginId')} placeholder="영문 · 숫자" />
          <Field label="비밀번호" value={form.password} onChangeText={set('password')} placeholder="비밀번호" secureTextEntry />
          <Field label="이름" value={form.name} onChangeText={set('name')} placeholder="이름" autoCapitalize="words" />
          <Field label="연락처" value={form.contact} onChangeText={set('contact')} placeholder="010-0000-0000" keyboardType="phone-pad" />
          {error ? <ErrorBox message={error} /> : null}
          <Button title="가입하기" busy={busy} onPress={submit} />
        </Card>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

export const st = StyleSheet.create({
  page: { padding: 16, gap: 12, paddingBottom: 40 },
  title: { color: C.ink, fontSize: 24, fontWeight: '800', marginTop: 4 },
  name: { color: C.ink, fontSize: 19, fontWeight: '800' },
  h2: { color: C.ink, fontSize: 16, fontWeight: '800' },
  muted: { color: C.sub, fontSize: 13, lineHeight: 19 },
  label: { color: C.sub, fontSize: 13, fontWeight: '600' },
  input: { backgroundColor: C.bg, borderRadius: 12, borderWidth: 1, borderColor: C.line, paddingHorizontal: 14, paddingVertical: 13, fontSize: 16, color: C.ink },
  link: { color: C.primary, fontWeight: '700', textAlign: 'center', paddingVertical: 4 },
  demoRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  demo: { backgroundColor: C.primarySoft, borderRadius: 999, paddingHorizontal: 16, paddingVertical: 10 },
  demoText: { color: C.primary, fontWeight: '700', fontSize: 14 },
});
