// FairTicket 앱 — 예제서버(FairChain 예제 예매 사이트)를 휴대폰에서 쓰는 화면.
// 아래 탭 4개(홈 · 내 티켓 · 취소표 · 내 정보) 위에 상세 화면이 차례로 쌓인다.
import { StatusBar } from 'expo-status-bar';
import { useEffect, useRef, useState } from 'react';
import { Animated, BackHandler, Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaProvider, useSafeAreaInsets } from 'react-native-safe-area-context';
import { loadServerUrl } from './src/api';
import { AppProvider, useApp, useNow } from './src/app';
import { Loading } from './src/components/ui';
import { Login, Me, Signup } from './src/screens/Account';
import Concert from './src/screens/Concert';
import Gate from './src/screens/Gate';
import Home from './src/screens/Home';
import Queue from './src/screens/Queue';
import Realloc from './src/screens/Realloc';
import Seats from './src/screens/Seats';
import Tickets from './src/screens/Tickets';
import { remaining } from './src/lib/utils';
import { C } from './src/theme';

const TAB_LIST = [
  { key: 'home', label: '홈', mark: '⌂', title: 'FairTicket', render: () => <Home /> },
  { key: 'tickets', label: '내 티켓', mark: '▤', title: '내 티켓', render: () => <Tickets /> },
  { key: 'realloc', label: '취소표', mark: '◔', title: '취소표 알림', render: () => <Realloc /> },
  { key: 'me', label: '내 정보', mark: '☺', title: '내 정보', render: () => <Me /> },
];

const SCREENS = {
  concert: { title: '공연 정보', render: (p) => <Concert {...p} /> },
  queue: { title: '대기열', render: (p) => <Queue {...p} /> },
  seats: { title: '좌석 선택', render: (p) => <Seats {...p} /> },
  login: { title: '로그인', render: (p) => <Login {...p} /> },
  signup: { title: '회원가입', render: (p) => <Signup {...p} /> },
  gate: { title: '입장 게이트', render: (p) => <Gate {...p} /> },
};

/** 취소표 알림 배너 — 어느 화면에 있든 위에서 내려온다. 누르면 그 취소표를 고르는 화면으로 */
function OfferBanner({ offer, top }) {
  const { nav, dismissBanner } = useApp();
  const now = useNow();
  const slide = useRef(new Animated.Value(-160)).current;
  useEffect(() => {
    Animated.spring(slide, { toValue: 0, useNativeDriver: true, friction: 9 }).start();
  }, [slide]);
  const seats = offer.seats.map((s) => `${s.gradeName}석 ${s.seatNumber}`).join(', ');
  return (
    <Animated.View style={[st.banner, { top: top + 6, transform: [{ translateY: slide }] }]}>
      <Pressable style={{ flex: 1, gap: 2 }} onPress={() => nav.push('seats', { id: offer.concertId, offerToken: offer.offerToken })}>
        <Text style={st.bannerTitle}>취소표가 나왔습니다 — 지금 내 차례</Text>
        <Text style={st.bannerText} numberOfLines={1}>{offer.concertTitle} · {seats}</Text>
        <Text style={st.bannerClock}>남은 시간 {remaining(offer.expiresAt, now)} · 눌러서 예매</Text>
      </Pressable>
      <Pressable hitSlop={12} onPress={() => dismissBanner(offer.offerId)}><Text style={st.bannerClose}>✕</Text></Pressable>
    </Animated.View>
  );
}

function Shell() {
  const { tab, setTab, stack, nav, toastMsg, offers, banner } = useApp();
  const insets = useSafeAreaInsets();
  const top = stack[stack.length - 1];
  const current = top ? SCREENS[top.name] : TAB_LIST.find((t) => t.key === tab);

  // 안드로이드 뒤로 버튼: 쌓인 화면이 있으면 하나 닫는다
  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (!stack.length) return false;
      nav.back();
      return true;
    });
    return () => sub.remove();
  }, [stack.length, nav]);

  return (
    <View style={[st.root, { paddingTop: insets.top }]}>
      <StatusBar style="dark" />
      <View style={st.header}>
        {top ? (
          <Pressable onPress={nav.back} hitSlop={12} style={st.back}><Text style={st.backText}>‹</Text></Pressable>
        ) : <View style={st.logo}><Text style={st.logoText}>F</Text></View>}
        {!top && tab === 'home'
          ? <Text style={st.headerTitle}>Fair<Text style={{ color: C.primary }}>Ticket</Text></Text>
          : <Text style={st.headerTitle} numberOfLines={1}>{current.title}</Text>}
      </View>

      <View style={{ flex: 1 }}>
        {/* 화면마다 key를 달아, 다른 공연으로 넘어가면 상태가 새로 시작되게 한다 */}
        {top
          ? <View key={`${stack.length}-${top.name}-${top.params?.id ?? ''}`} style={{ flex: 1 }}>{current.render(top.params)}</View>
          : <View key={tab} style={{ flex: 1 }}>{current.render()}</View>}
      </View>

      {!top && (
        <View style={[st.tabs, { paddingBottom: Math.max(insets.bottom, 8) }]}>
          {TAB_LIST.map((t) => (
            <Pressable key={t.key} style={st.tab} onPress={() => setTab(t.key)}>
              <View>
                <Text style={[st.tabMark, tab === t.key && st.tabOn]}>{t.mark}</Text>
                {t.key === 'realloc' && offers.length > 0 && <View style={st.dot}><Text style={st.dotText}>{offers.length}</Text></View>}
              </View>
              <Text style={[st.tabLabel, tab === t.key && st.tabOn]}>{t.label}</Text>
            </Pressable>
          ))}
        </View>
      )}

      {/* 이미 그 취소표를 고르는 화면이면 배너를 겹쳐 띄우지 않는다 */}
      {banner && !(top?.name === 'seats' && top.params?.offerToken === banner.offerToken) && (
        <OfferBanner key={banner.offerId} offer={banner} top={insets.top} />
      )}

      {toastMsg && (
        <View pointerEvents="none" style={[st.toast, { bottom: insets.bottom + 90 }, toastMsg.kind === 'bad' && { backgroundColor: C.bad }]}>
          <Text style={st.toastText}>{toastMsg.message}</Text>
        </View>
      )}
    </View>
  );
}

export default function App() {
  const [ready, setReady] = useState(false);
  useEffect(() => { loadServerUrl().finally(() => setReady(true)); }, []);   // 서버 주소를 먼저 읽고 나서 화면을 그린다
  return (
    <SafeAreaProvider>
      {ready ? <AppProvider><Shell /></AppProvider> : <View style={[st.root, { justifyContent: 'center' }]}><Loading /></View>}
    </SafeAreaProvider>
  );
}

const st = StyleSheet.create({
  root: { flex: 1, backgroundColor: C.bg },
  header: { flexDirection: 'row', alignItems: 'center', gap: 10, paddingHorizontal: 16, height: 54, backgroundColor: C.card, borderBottomWidth: 1, borderBottomColor: C.line },
  logo: { width: 30, height: 30, borderRadius: 9, backgroundColor: C.primary, alignItems: 'center', justifyContent: 'center' },
  logoText: { color: '#fff', fontWeight: '900', fontSize: 16 },
  back: { width: 30, height: 30, alignItems: 'center', justifyContent: 'center' },
  backText: { color: C.ink, fontSize: 32, lineHeight: 34, fontWeight: '400' },
  headerTitle: { flex: 1, color: C.ink, fontSize: 19, fontWeight: '800' },
  tabs: { flexDirection: 'row', backgroundColor: C.card, borderTopWidth: 1, borderTopColor: C.line, paddingTop: 8 },
  tab: { flex: 1, alignItems: 'center', gap: 2 },
  tabMark: { fontSize: 20, color: C.faint },
  tabLabel: { fontSize: 11, color: C.faint, fontWeight: '700' },
  tabOn: { color: C.primary },
  banner: { position: 'absolute', left: 10, right: 10, flexDirection: 'row', alignItems: 'center', gap: 10, backgroundColor: C.dark, borderRadius: 18, paddingVertical: 12, paddingHorizontal: 16, borderLeftWidth: 5, borderLeftColor: '#fbbf24', elevation: 12, shadowColor: '#000', shadowOpacity: 0.3, shadowRadius: 12, shadowOffset: { width: 0, height: 6 } },
  bannerTitle: { color: '#fde68a', fontSize: 14, fontWeight: '800' },
  bannerText: { color: '#fff', fontSize: 14, fontWeight: '700' },
  bannerClock: { color: '#d4d3df', fontSize: 12, fontVariant: ['tabular-nums'] },
  bannerClose: { color: '#a3a2b0', fontSize: 18, padding: 4 },
  dot: { position: 'absolute', top: -3, right: -12, minWidth: 17, height: 17, borderRadius: 9, backgroundColor: C.pink, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 4 },
  dotText: { color: '#fff', fontSize: 10, fontWeight: '800' },
  toast: { position: 'absolute', left: 20, right: 20, backgroundColor: C.dark, borderRadius: 14, paddingVertical: 13, paddingHorizontal: 16, alignItems: 'center' },
  toastText: { color: '#fff', fontSize: 14, fontWeight: '600', textAlign: 'center' },
});
