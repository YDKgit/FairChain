import { useEffect } from 'react';
import { Route, Routes, useLocation } from 'react-router-dom';
import Header from './components/Header.jsx';
import Footer from './components/Footer.jsx';
import Toast from './components/Toast.jsx';
import Home from './pages/Home.jsx';
import Concerts from './pages/Concerts.jsx';
import ConcertDetail from './pages/ConcertDetail.jsx';
import Queue from './pages/Queue.jsx';
import Seats from './pages/Seats.jsx';
import MyTickets from './pages/MyTickets.jsx';
import Reallocation from './pages/Reallocation.jsx';
import Login from './pages/Login.jsx';
import Signup from './pages/Signup.jsx';
import Admin from './pages/Admin.jsx';
import Gate from './pages/Gate.jsx';
import { useAuth } from './auth.jsx';

export default function App() {
  const { ready } = useAuth();
  const { pathname, search } = useLocation();
  const bare = pathname.startsWith('/admin') || pathname.startsWith('/gate');   // 운영자 · 게이트는 따로 띄우는 창
  const focus = pathname.startsWith('/queue') || pathname.startsWith('/seats'); // 예매 중에는 메뉴를 줄인다

  useEffect(() => { window.scrollTo(0, 0); }, [pathname, search]);

  if (!ready) {
    return <div className="boot">불러오는 중…</div>;
  }
  return (
    <div className={`app ${bare ? 'app-bare' : ''} ${focus ? 'app-focus' : ''}`}>
      {!bare && <Header />}
      <main className="page">
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/concerts" element={<Concerts />} />
          <Route path="/concert/:id" element={<ConcertDetail />} />
          <Route path="/queue/:id" element={<Queue />} />
          <Route path="/seats/:id" element={<Seats />} />
          <Route path="/tickets" element={<MyTickets />} />
          <Route path="/reallocation" element={<Reallocation />} />
          <Route path="/login" element={<Login />} />
          <Route path="/signup" element={<Signup />} />
          <Route path="/admin/*" element={<Admin />} />
          <Route path="/gate" element={<Gate />} />
          <Route path="*" element={<div className="container"><div className="empty">페이지가 없습니다</div></div>} />
        </Routes>
      </main>
      {!bare && <Footer />}
      <Toast />
    </div>
  );
}
