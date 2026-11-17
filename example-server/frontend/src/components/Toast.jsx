import { useAuth } from '../auth.jsx';

export default function Toast() {
  const { toastMsg } = useAuth();
  if (!toastMsg) return null;
  return <div className={`toast ${toastMsg.kind}`}>{toastMsg.message}</div>;
}
