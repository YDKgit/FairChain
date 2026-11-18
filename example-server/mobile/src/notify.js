// 휴대폰 알림(화면 위에서 내려오는 배너). Expo Go에서는 "앱이 직접 띄우는 알림"만 쓸 수 있어서,
// 앱이 서버에 물어보다가 새 취소표 제안을 발견하면 여기서 알림을 띄운다.
import * as Notifications from 'expo-notifications';
import { LogBox, Platform } from 'react-native';

const CHANNEL = 'realloc';
const native = Platform.OS !== 'web';

// Expo Go에서는 서버가 보내는 알림(푸시)을 못 쓴다는 안내가 매번 뜬다 — 이 앱은 쓰지 않으므로 숨긴다
LogBox.ignoreLogs(['expo-notifications', '`expo-notifications`']);

if (native) {
  // 앱을 보고 있는 중에도 배너 · 소리가 나오게 한다
  Notifications.setNotificationHandler({
    handleNotification: async () => ({
      shouldShowBanner: true, shouldShowList: true, shouldPlaySound: true, shouldSetBadge: false,
    }),
  });
}

/** 알림 권한을 받고(처음 한 번 물어봄) 안드로이드 알림 채널을 만든다. 허용되면 true */
export async function setupNotifications() {
  if (!native) return false;
  try {
    if (Platform.OS === 'android') {
      await Notifications.setNotificationChannelAsync(CHANNEL, {
        name: '취소표 알림',
        importance: Notifications.AndroidImportance.MAX,     // 화면 위에 배너로 내려오려면 가장 높은 단계여야 한다
        vibrationPattern: [0, 250, 150, 250],
        lockscreenVisibility: Notifications.AndroidNotificationVisibility.PUBLIC,
      });
    }
    let { granted } = await Notifications.getPermissionsAsync();
    if (!granted) ({ granted } = await Notifications.requestPermissionsAsync());
    return granted;
  } catch { return false; }
}

/** 새 취소표 제안을 휴대폰 알림으로 띄운다 */
export async function notifyOffer(offer) {
  if (!native) return;
  try {
    const seats = (offer.seats || []).map((s) => `${s.gradeName}석 ${s.seatNumber}`).join(', ');
    await Notifications.scheduleNotificationAsync({
      content: {
        title: '취소표가 나왔습니다 — 지금 내 차례',
        body: `${offer.concertTitle}${seats ? ` · ${seats}` : ''}\n기한 안에 눌러서 예매하세요.`,
        sound: true,
        data: { concertId: offer.concertId, offerToken: offer.offerToken },
      },
      trigger: Platform.OS === 'android' ? { channelId: CHANNEL } : null,   // 바로 띄운다
    });
  } catch { /* 알림을 못 띄워도 앱 안 배너는 나온다 */ }
}

/** 알림을 눌렀을 때 부를 함수를 건다. 돌려주는 함수를 부르면 해제 */
export function onNotificationTap(handler) {
  if (!native) return () => {};
  const sub = Notifications.addNotificationResponseReceivedListener((response) => {
    const data = response?.notification?.request?.content?.data;
    if (data?.offerToken) handler(data);
  });
  return () => sub.remove();
}
