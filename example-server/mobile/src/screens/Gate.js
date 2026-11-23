// 입장 게이트 — 스태프가 관객의 입장 QR을 휴대폰 카메라로 찍으면 FairChain 기록과 대조해 허용 · 거절
import AsyncStorage from '@react-native-async-storage/async-storage';
import { CameraView, useCameraPermissions } from 'expo-camera';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Pressable, ScrollView, StyleSheet, Text, Vibration, View } from 'react-native';
import { get, post } from '../api';
import { Badge, Button, Card, ErrorBox, Loading } from '../components/ui';
import { clock } from '../lib/utils';
import { C } from '../theme';
import { Field } from './Account';

// 스캔 기록은 휴대폰에 남긴다 — 다른 화면에 다녀오거나 앱을 다시 켜도 보인다
const HISTORY_KEY = 'fairticket.gateHistory';
const HISTORY_MAX = 200;

export default function Gate() {
  const [staff, setStaff] = useState(undefined);      // undefined: 확인 중, null: 로그인 필요
  const [id, setId] = useState('staff');
  const [pw, setPw] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const [permission, requestPermission] = useCameraPermissions();
  const [scanning, setScanning] = useState(false);
  const [result, setResult] = useState(null);
  const [history, setHistory] = useState([]);
  const [filter, setFilter] = useState('');           // '' = 전체, 아니면 공연 이름
  const loaded = useRef(false);
  const [token, setToken] = useState('');
  const lock = useRef(false);

  useEffect(() => { get('/api/staff/me').then(setStaff).catch(() => setStaff(null)); }, []);

  useEffect(() => {
    AsyncStorage.getItem(HISTORY_KEY)
      .then((v) => { const saved = JSON.parse(v || '[]'); if (Array.isArray(saved)) setHistory((h) => [...h, ...saved].slice(0, HISTORY_MAX)); })
      .catch(() => { /* 저장된 기록을 못 읽어도 새 기록은 쌓인다 */ })
      .finally(() => { loaded.current = true; });
  }, []);
  useEffect(() => {
    if (loaded.current) AsyncStorage.setItem(HISTORY_KEY, JSON.stringify(history)).catch(() => { /* 무시 */ });
  }, [history]);

  const concerts = [...new Set(history.map((h) => h.concertTitle).filter(Boolean))];
  const shown = filter ? history.filter((h) => h.concertTitle === filter) : history;
  const clearHistory = () => Alert.alert('스캔 기록 지우기', '이 휴대폰에 남은 스캔 기록을 지울까요?', [
    { text: '취소', style: 'cancel' },
    { text: '지우기', style: 'destructive', onPress: () => { setHistory([]); setFilter(''); } },
  ]);

  const login = async () => {
    setBusy(true); setError('');
    try { setStaff(await post('/api/staff/login', { loginId: id.trim(), password: pw })); }
    catch (e) { setError(e.message); }
    setBusy(false);
  };

  const last = useRef({ token: '', at: 0 });

  const check = useCallback(async (value, fromCamera = false) => {
    const qrToken = String(value || '').trim();
    if (!qrToken || lock.current) return;
    if (fromCamera) {
      // 방금 확인한 QR이 아직 카메라 앞에 있으면 다시 보내지 않는다 (치우고 5초가 지나야 다시 확인)
      const again = qrToken === last.current.token && Date.now() - last.current.at < 5000;
      last.current = { token: qrToken, at: Date.now() };
      if (again) return;
    }
    lock.current = true;                         // 카메라는 같은 QR을 1초에도 여러 번 알려 준다 — 한 번만 보낸다
    setScanning(false);
    let r;
    try { r = await post('/api/staff/scan', { qrToken }); }
    catch (e) { r = { success: false, reason: e.message }; }
    Vibration.vibrate(r.success ? 80 : [0, 120, 80, 120]);
    setResult(r);
    setHistory((h) => [{ ...r, at: Date.now(), token: qrToken.slice(0, 8) }, ...h].slice(0, HISTORY_MAX));
    setToken('');
    last.current.at = Date.now();
    lock.current = false;
  }, []);

  const startScan = async () => {
    if (!permission?.granted) {
      const p = await requestPermission();
      if (!p.granted) { setError('카메라 권한이 필요합니다. 휴대폰 설정에서 Expo Go의 카메라 권한을 허용해 주세요.'); return; }
    }
    setError(''); setResult(null); setScanning(true);
  };

  if (staff === undefined) return <Loading />;

  if (staff === null) {
    return (
      <ScrollView contentContainerStyle={st.page} keyboardShouldPersistTaps="handled">
        <Text style={st.title}>게이트 스태프 로그인</Text>
        <Card style={{ gap: 12 }}>
          <Field label="아이디" value={id} onChangeText={setId} />
          <Field label="비밀번호" value={pw} onChangeText={setPw} secureTextEntry onSubmitEditing={login} />
          {error ? <ErrorBox message={error} /> : null}
          <Button title="로그인" busy={busy} onPress={login} />
          <Text style={st.muted}>스태프 계정은 운영자 화면에서 만듭니다. 로컬 실행 기본값은 staff / staff1234 입니다.</Text>
        </Card>
      </ScrollView>
    );
  }

  return (
    <ScrollView contentContainerStyle={st.page} keyboardShouldPersistTaps="handled">
      <View style={st.rowBetween}>
        <Text style={st.title}>입장 게이트</Text>
        <Text style={st.muted} onPress={async () => { try { await post('/api/staff/logout'); } catch { /* 무시 */ } setStaff(null); }}>
          {staff.name} · 로그아웃
        </Text>
      </View>

      {scanning ? (
        <View style={st.cameraBox}>
          <CameraView
            style={st.camera}
            facing="back"
            barcodeScannerSettings={{ barcodeTypes: ['qr'] }}
            onBarcodeScanned={({ data }) => check(data, true)}
          />
          <View style={st.frame} pointerEvents="none" />
          <Text style={st.cameraHint}>관객의 입장 QR을 네모 안에 맞추세요</Text>
        </View>
      ) : (
        result && (
          <View style={[st.result, result.success ? st.resultOk : st.resultBad]}>
            <Text style={[st.mark, { color: result.success ? C.ok : C.bad }]}>{result.success ? '✓' : '✕'}</Text>
            <Text style={[st.resultTitle, { color: result.success ? C.ok : C.bad }]}>{result.success ? '입장 허용' : '입장 거절'}</Text>
            <Text style={st.resultText}>{result.success ? `${result.concertTitle}\n${result.seatNumber}` : result.concertTitle ? `${result.reason}\n${result.concertTitle} · ${result.seatNumber}` : result.reason}</Text>
            <Text style={st.muted}>{result.success ? '블록체인 기록과 일치 — 입장 기록을 FairChain에 보냈습니다' : '블록체인 기록과 대조한 결과입니다'}</Text>
          </View>
        )
      )}

      {error ? <ErrorBox message={error} /> : null}
      <Button title={scanning ? '스캔 멈추기' : result ? '다음 관객 스캔' : 'QR 스캔 시작'} kind={scanning ? 'ghost' : 'primary'} onPress={scanning ? () => setScanning(false) : startScan} />

      <Card style={{ gap: 10 }}>
        <Text style={st.h2}>토큰 직접 입력</Text>
        <Field label="QR 토큰" value={token} onChangeText={setToken} placeholder="카메라가 안 될 때" onSubmitEditing={() => check(token)} />
        <Button title="확인" kind="ghost" disabled={!token.trim()} onPress={() => check(token)} />
      </Card>

      {history.length > 0 && (
        <Card style={{ gap: 10 }}>
          <View style={st.rowBetween}>
            <Text style={st.h2}>스캔 기록</Text>
            <Text style={st.muted} onPress={clearHistory}>기록 지우기</Text>
          </View>
          <View style={st.chips}>
            <Chip on={filter === ''} onPress={() => setFilter('')}>전체 {history.length}</Chip>
            {concerts.map((c) => (
              <Chip key={c} on={filter === c} onPress={() => setFilter(c)}>{c} {history.filter((h) => h.concertTitle === c).length}</Chip>
            ))}
          </View>
          <Text style={st.count}>허용 {shown.filter((h) => h.success).length}건 · 거절 {shown.filter((h) => !h.success).length}건</Text>
          {shown.map((h, i) => (
            <View key={i} style={st.historyRow}>
              <Text style={st.historyTime}>{clock(h.at)}</Text>
              <Badge kind={h.success ? 'ok' : 'bad'}>{h.success ? '허용' : '거절'}</Badge>
              <View style={{ flex: 1 }}>
                <Text style={st.historyText} numberOfLines={1}>{h.concertTitle ? `${h.concertTitle} · ${h.seatNumber}` : h.reason}</Text>
                {!h.success && h.concertTitle ? <Text style={st.historySub} numberOfLines={1}>{h.reason}</Text> : null}
              </View>
            </View>
          ))}
        </Card>
      )}
    </ScrollView>
  );
}

function Chip({ on, onPress, children }) {
  return (
    <Pressable onPress={onPress} style={[st.chip, on && st.chipOn]}>
      <Text style={[st.chipText, on && st.chipTextOn]} numberOfLines={1}>{children}</Text>
    </Pressable>
  );
}

const st = StyleSheet.create({
  page: { padding: 16, gap: 12, paddingBottom: 40 },
  title: { color: C.ink, fontSize: 24, fontWeight: '800' },
  h2: { color: C.ink, fontSize: 16, fontWeight: '800' },
  muted: { color: C.sub, fontSize: 13, lineHeight: 19, textAlign: 'center' },
  rowBetween: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  cameraBox: { height: 380, borderRadius: 22, overflow: 'hidden', backgroundColor: '#000', alignItems: 'center', justifyContent: 'center' },
  camera: { ...StyleSheet.absoluteFill },
  frame: { width: 230, height: 230, borderRadius: 22, borderWidth: 3, borderColor: '#fff' },
  cameraHint: { position: 'absolute', bottom: 16, color: '#fff', fontSize: 14, fontWeight: '700' },
  result: { borderRadius: 22, padding: 24, alignItems: 'center', gap: 6 },
  resultOk: { backgroundColor: C.okSoft },
  resultBad: { backgroundColor: C.badSoft },
  mark: { fontSize: 56, fontWeight: '800', lineHeight: 62 },
  resultTitle: { fontSize: 26, fontWeight: '800' },
  resultText: { color: C.ink, fontSize: 16, fontWeight: '700', textAlign: 'center', lineHeight: 23 },
  historyRow: { flexDirection: 'row', gap: 8, alignItems: 'center' },
  historyTime: { color: C.sub, fontSize: 12, width: 40, fontVariant: ['tabular-nums'] },
  historyText: { color: C.ink, fontSize: 13 },
  historySub: { color: C.sub, fontSize: 12, marginTop: 1 },
  count: { color: C.sub, fontSize: 13 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  chip: { maxWidth: '100%', paddingVertical: 6, paddingHorizontal: 12, borderRadius: 999, borderWidth: 1, borderColor: C.sub, backgroundColor: '#fff' },
  chipOn: { backgroundColor: C.ink, borderColor: C.ink },
  chipText: { color: C.ink, fontSize: 13, fontWeight: '600' },
  chipTextOn: { color: '#fff' },
});
