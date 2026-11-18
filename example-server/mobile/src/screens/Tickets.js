// 내 티켓 — 티켓 카드와 입장 QR. 카드를 누르면 QR을 크게 띄운다(게이트에서 이 QR을 찍는다)
import { useCallback, useState } from 'react';
import { Alert, Modal, Pressable, RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';
import QRCode from 'react-native-qrcode-svg';
import { get, post } from '../api';
import { useApp, usePolling } from '../app';
import { Badge, Button, Empty, ErrorBox, Loading, VerifyBadge } from '../components/ui';
import { ticketStatusText, verificationBadge } from '../lib/labels';
import { when, won } from '../lib/utils';
import { C } from '../theme';

export default function Tickets() {
  const { member, nav, toast } = useApp();
  const [tickets, setTickets] = useState(null);
  const [error, setError] = useState('');
  const [open, setOpen] = useState(null);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(async () => {
    if (!member) return;
    try { setTickets((await get('/api/tickets')).tickets || []); setError(''); }
    catch (e) { setError(e.message); }
  }, [member]);
  usePolling(load, 4000, !!member);

  if (!member) {
    return (
      <View style={st.page}>
        <Empty>로그인하면 예매한 티켓과 입장 QR을 볼 수 있습니다.</Empty>
        <Button title="로그인" onPress={() => nav.push('login', {})} />
      </View>
    );
  }

  const cancel = (t) => {
    Alert.alert('예매 취소', `${t.concertTitle}\n${t.seatGrade}석 ${t.seatNumber}\n\n취소하시겠습니까? 매진된 공연이면 이 좌석은 대기 순번대로 재배정됩니다.`, [
      { text: '아니요', style: 'cancel' },
      {
        text: '취소하기', style: 'destructive',
        onPress: async () => {
          try { await post(`/api/tickets/${t.ticketId}/cancel`); toast('예매를 취소했습니다'); setOpen(null); load(); }
          catch (e) { toast(e.message, 'bad'); }
        },
      },
    ]);
  };

  const live = open && (tickets || []).find((t) => t.ticketId === open.ticketId) || open;
  const active = (tickets || []).filter((t) => t.status === 'ACTIVE');
  const past = (tickets || []).filter((t) => t.status !== 'ACTIVE');

  return (
    <>
      <ScrollView
        contentContainerStyle={st.page}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={async () => { setRefreshing(true); await load(); setRefreshing(false); }} />}>
        {error ? <ErrorBox message={error} onRetry={load} /> : null}
        {!tickets && !error && <Loading />}
        {tickets && !tickets.length && <Empty>아직 예매한 티켓이 없습니다.{'\n'}홈에서 공연을 골라 예매해 보세요.</Empty>}
        {active.map((t) => <TicketCard key={t.ticketId ?? t.recordKey} t={t} onOpen={() => setOpen(t)} />)}
        {past.length > 0 && <Text style={st.section}>지난 티켓</Text>}
        {past.map((t) => <TicketCard key={t.ticketId ?? t.recordKey} t={t} />)}
      </ScrollView>

      <Modal visible={!!live} animationType="slide" transparent onRequestClose={() => setOpen(null)}>
        {live && (
          <View style={st.backdrop}>
            <View style={st.sheet}>
              <Text style={st.sheetTitle}>{live.concertTitle}</Text>
              <Text style={st.muted}>{live.seatGrade}석 {live.seatNumber}</Text>
              <View style={st.qrBox}><QRCode value={String(live.qrToken)} size={230} /></View>
              <VerifyBadge status={live.verificationStatus} />
              <Text style={[st.muted, { textAlign: 'center' }]}>{(verificationBadge[live.verificationStatus] || [])[2] || ''}</Text>
              <Text style={st.mono}>기록 v{live.version}{live.blockIndex != null ? ` · 블록 #${live.blockIndex}` : ' · 블록 확정 전'}</Text>
              <Text style={st.hint}>화면 밝기를 올리고 게이트에서 이 QR을 보여 주세요.</Text>
              <Button title="닫기" onPress={() => setOpen(null)} style={{ alignSelf: 'stretch' }} />
              <Pressable onPress={() => cancel(live)}><Text style={st.cancel}>예매 취소</Text></Pressable>
            </View>
          </View>
        )}
      </Modal>
    </>
  );
}

function TicketCard({ t, onOpen }) {
  const used = t.status === 'CHECKED_IN';
  const local = !!t.ticketId;
  const canOpen = local && t.status === 'ACTIVE' && onOpen;
  return (
    <Pressable style={[st.ticket, !canOpen && { opacity: 0.72 }]} onPress={canOpen ? onOpen : undefined}>
      <View style={st.ticketMain}>
        <View style={st.row}>
          <VerifyBadge status={t.verificationStatus} />
          <Badge kind={used ? 'ok' : 'muted'}>{ticketStatusText[t.status] || t.status}</Badge>
        </View>
        <Text style={st.ticketTitle} numberOfLines={2}>{t.concertTitle}</Text>
        <Text style={st.seat}>{t.seatGrade}석 {t.seatNumber}</Text>
        {local ? (
          <Text style={st.meta}>
            예매번호 T{String(t.ticketId).padStart(6, '0')} · {won(t.price)}{'\n'}예매 {when(t.issuedAt)}
            {t.checkedInAt ? `\n입장 ${when(t.checkedInAt)}` : ''}
          </Text>
        ) : <Text style={st.meta}>예매 사이트에는 없는데 블록체인에는 내 티켓으로 남아 있는 기록입니다.</Text>}
      </View>
      <View style={st.stub}>
        {canOpen ? (
          <>
            <QRCode value={String(t.qrToken)} size={74} />
            <Text style={st.stubText}>눌러서 크게</Text>
          </>
        ) : <Text style={st.stubText}>{ticketStatusText[t.status] || ''}</Text>}
      </View>
    </Pressable>
  );
}

const st = StyleSheet.create({
  page: { padding: 16, gap: 12, paddingBottom: 32 },
  section: { color: C.sub, fontSize: 13, fontWeight: '700', marginTop: 8 },
  row: { flexDirection: 'row', gap: 6, flexWrap: 'wrap' },
  ticket: { flexDirection: 'row', backgroundColor: C.card, borderRadius: 20, borderWidth: 1, borderColor: C.line, overflow: 'hidden' },
  ticketMain: { flex: 1, padding: 16, gap: 6 },
  ticketTitle: { color: C.ink, fontSize: 16, fontWeight: '800', lineHeight: 21 },
  seat: { color: C.primary, fontSize: 18, fontWeight: '800' },
  meta: { color: C.sub, fontSize: 12, lineHeight: 18 },
  stub: { width: 108, alignItems: 'center', justifyContent: 'center', gap: 6, borderLeftWidth: 1, borderLeftColor: C.line, borderStyle: 'dashed', backgroundColor: '#fafaff', padding: 10 },
  stubText: { color: C.sub, fontSize: 11, fontWeight: '600', textAlign: 'center' },
  backdrop: { flex: 1, backgroundColor: 'rgba(15,14,24,0.62)', justifyContent: 'flex-end' },
  sheet: { backgroundColor: C.card, borderTopLeftRadius: 28, borderTopRightRadius: 28, padding: 24, paddingBottom: 34, gap: 10, alignItems: 'center' },
  sheetTitle: { color: C.ink, fontSize: 18, fontWeight: '800', textAlign: 'center' },
  qrBox: { padding: 14, backgroundColor: '#fff', borderRadius: 18, borderWidth: 1, borderColor: C.line, marginVertical: 6 },
  muted: { color: C.sub, fontSize: 13, lineHeight: 19 },
  mono: { color: C.sub, fontSize: 12, fontVariant: ['tabular-nums'] },
  hint: { color: C.primary, fontSize: 13, fontWeight: '600', textAlign: 'center' },
  cancel: { color: C.bad, fontSize: 14, textDecorationLine: 'underline', paddingVertical: 6 },
});
