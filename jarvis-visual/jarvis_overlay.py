"""
Jarvis - visuel de bureau (Windows).

Un orbe animé qui vit par-dessus tout l'écran : il vole, se pose sur le haut des fenêtres,
suit leurs déplacements, réagit à la souris et peut être attrapé et lancé.
Ce fichier ne contient QUE le visuel. Ton Jarvis le pilote par des commandes locales (voir README).
"""
import argparse
import math
import os
import random
import sys
import time
from collections import deque
from dataclasses import dataclass, field

# Coordonnées Qt = pixels réels, identiques à celles de Windows (important sur plusieurs écrans)
os.environ.setdefault("QT_ENABLE_HIGHDPI_SCALING", "0")

from PySide6.QtCore import QElapsedTimer, QPointF, QRect, QRectF, Qt, QTimer
from PySide6.QtGui import (QBrush, QColor, QFont, QFontMetricsF, QGuiApplication, QIcon, QPainter,
                           QPainterPath, QPen, QPixmap, QRadialGradient, QRegion, QCursor, QLinearGradient)
from PySide6.QtNetwork import QHostAddress, QUdpSocket
from PySide6.QtWidgets import QApplication, QMenu, QSystemTrayIcon, QWidget

IS_WIN = sys.platform == "win32"

# --------------------------------------------------------------------------------------
#  Accès au bureau (fenêtres, souris)
# --------------------------------------------------------------------------------------


@dataclass
class Win:
    hwnd: int
    rect: QRect
    title: str = ""
    cls: str = ""


class FakeDesktop:
    """Bureau factice pour tester hors Windows."""

    def __init__(self, wins=None):
        self.wins = wins or []
        self.cursor_pos = QPointF(400, 300)
        self.down = False

    def windows(self): return list(self.wins)
    def rect_of(self, hwnd):
        for w in self.wins:
            if w.hwnd == hwnd:
                return QRect(w.rect)
        return None
    def cursor(self): return QPointF(self.cursor_pos)
    def mouse_down(self): return self.down
    def fullscreen_app(self): return False
    def apply_style(self, widget): pass
    def set_passthrough(self, widget, on): pass
    def keep_on_top(self, widget): pass


class WinDesktop:
    """Bureau Windows réel, via ctypes (aucune bibliothèque en plus)."""

    GWL_EXSTYLE = -20
    WS_EX_TRANSPARENT = 0x20
    WS_EX_TOOLWINDOW = 0x80
    WS_EX_APPWINDOW = 0x40000
    WS_EX_LAYERED = 0x80000
    WS_EX_NOACTIVATE = 0x08000000
    SKIP = {"Progman", "WorkerW", "Windows.UI.Core.CoreWindow", "XamlExplorerHostIslandWindow"}
    TASKBAR = {"Shell_TrayWnd", "Shell_SecondaryTrayWnd"}

    def __init__(self):
        import ctypes
        from ctypes import wintypes
        self.ct, self.wt = ctypes, wintypes
        self.u = ctypes.windll.user32
        self.dwm = ctypes.windll.dwmapi
        u = self.u

        class RECT(ctypes.Structure):
            _fields_ = [("left", ctypes.c_long), ("top", ctypes.c_long), ("right", ctypes.c_long), ("bottom", ctypes.c_long)]

        class MONITORINFO(ctypes.Structure):
            _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", RECT), ("rcWork", RECT), ("dwFlags", wintypes.DWORD)]

        self.RECT, self.MONITORINFO = RECT, MONITORINFO
        self.ENUMPROC = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
        u.GetWindowLongPtrW.restype = ctypes.c_ssize_t
        u.GetWindowLongPtrW.argtypes = [wintypes.HWND, ctypes.c_int]
        u.SetWindowLongPtrW.restype = ctypes.c_ssize_t
        u.SetWindowLongPtrW.argtypes = [wintypes.HWND, ctypes.c_int, ctypes.c_ssize_t]
        u.IsWindowVisible.argtypes = [wintypes.HWND]
        u.IsIconic.argtypes = [wintypes.HWND]
        u.GetWindowRect.argtypes = [wintypes.HWND, ctypes.POINTER(RECT)]
        u.GetWindowTextLengthW.argtypes = [wintypes.HWND]
        u.GetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
        u.GetClassNameW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
        u.GetForegroundWindow.restype = wintypes.HWND
        u.MonitorFromWindow.argtypes = [wintypes.HWND, wintypes.DWORD]
        u.MonitorFromWindow.restype = wintypes.HANDLE
        u.GetMonitorInfoW.argtypes = [wintypes.HANDLE, ctypes.POINTER(MONITORINFO)]
        u.SetWindowPos.argtypes = [wintypes.HWND, wintypes.HWND, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, wintypes.UINT]
        u.GetAsyncKeyState.argtypes = [ctypes.c_int]
        self.dwm.DwmGetWindowAttribute.argtypes = [wintypes.HWND, wintypes.DWORD, ctypes.c_void_p, wintypes.DWORD]
        self.own = 0

    # -- utilitaires
    def _text(self, fn, hwnd, n=256):
        buf = self.ct.create_unicode_buffer(n)
        fn(hwnd, buf, n)
        return buf.value

    def _frame(self, hwnd):
        r = self.RECT()
        # Cadre visible réel (sans l'ombre invisible de Windows 10/11)
        if self.dwm.DwmGetWindowAttribute(hwnd, 9, self.ct.byref(r), self.ct.sizeof(r)) != 0:
            if not self.u.GetWindowRect(hwnd, self.ct.byref(r)):
                return None
        return QRect(r.left, r.top, r.right - r.left, r.bottom - r.top)

    # -- interface
    def windows(self):
        out = []
        ct, u = self.ct, self.u

        def cb(hwnd, _):
            try:
                if hwnd == self.own or not u.IsWindowVisible(hwnd) or u.IsIconic(hwnd):
                    return True
                cls = self._text(u.GetClassNameW, hwnd, 128)
                if cls in self.SKIP:
                    return True
                cloaked = ct.c_int(0)
                self.dwm.DwmGetWindowAttribute(hwnd, 14, ct.byref(cloaked), 4)
                if cloaked.value:
                    return True
                ex = u.GetWindowLongPtrW(hwnd, self.GWL_EXSTYLE)
                taskbar = cls in self.TASKBAR
                if (ex & self.WS_EX_TOOLWINDOW) and not (ex & self.WS_EX_APPWINDOW) and not taskbar:
                    return True
                title = self._text(u.GetWindowTextW, hwnd) if u.GetWindowTextLengthW(hwnd) else ""
                if not title and not taskbar:
                    return True
                r = self._frame(hwnd)
                if r is None or r.width() < 160 or (r.height() < 80 and not taskbar):
                    return True
                out.append(Win(int(hwnd), r, title, cls))
            except Exception:
                pass
            return True

        u.EnumWindows(self.ENUMPROC(cb), 0)   # ordre : du plus haut au plus bas
        return out

    def rect_of(self, hwnd):
        try:
            if not self.u.IsWindowVisible(hwnd) or self.u.IsIconic(hwnd):
                return None
            return self._frame(hwnd)
        except Exception:
            return None

    def cursor(self):
        p = QCursor.pos()
        return QPointF(p.x(), p.y())

    def mouse_down(self):
        return bool(self.u.GetAsyncKeyState(0x01) & 0x8000)

    def fullscreen_app(self):
        try:
            h = self.u.GetForegroundWindow()
            if not h or int(h) == self.own:
                return False
            if self._text(self.u.GetClassNameW, h, 128) in (self.SKIP | self.TASKBAR):
                return False
            r = self.RECT()
            self.u.GetWindowRect(h, self.ct.byref(r))
            mi = self.MONITORINFO()
            mi.cbSize = self.ct.sizeof(mi)
            self.u.GetMonitorInfoW(self.u.MonitorFromWindow(h, 2), self.ct.byref(mi))
            m = mi.rcMonitor
            return r.left <= m.left and r.top <= m.top and r.right >= m.right and r.bottom >= m.bottom
        except Exception:
            return False

    def apply_style(self, widget):
        self.own = int(widget.winId())
        ex = self.u.GetWindowLongPtrW(self.own, self.GWL_EXSTYLE)
        ex |= self.WS_EX_LAYERED | self.WS_EX_TOOLWINDOW | self.WS_EX_NOACTIVATE | self.WS_EX_TRANSPARENT
        self.u.SetWindowLongPtrW(self.own, self.GWL_EXSTYLE, ex)
        self.keep_on_top(widget)

    def set_passthrough(self, widget, on):
        h = int(widget.winId())
        ex = self.u.GetWindowLongPtrW(h, self.GWL_EXSTYLE)
        ex = (ex | self.WS_EX_TRANSPARENT) if on else (ex & ~self.WS_EX_TRANSPARENT)
        self.u.SetWindowLongPtrW(h, self.GWL_EXSTYLE, ex)

    def keep_on_top(self, widget):
        # HWND_TOPMOST = -1 ; SWP_NOSIZE | SWP_NOMOVE | SWP_NOACTIVATE
        self.u.SetWindowPos(int(widget.winId()), self.wt.HWND(-1), 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0010)


# --------------------------------------------------------------------------------------
#  Plateformes : le haut des fenêtres visibles
# --------------------------------------------------------------------------------------


@dataclass
class Seg:
    y: int
    x1: float
    x2: float
    hwnd: int


def _subtract(intervals, a, b):
    out = []
    for x1, x2 in intervals:
        if b <= x1 or a >= x2:
            out.append((x1, x2))
            continue
        if a > x1:
            out.append((x1, a))
        if b < x2:
            out.append((b, x2))
    return out


def compute_platforms(wins, min_top, min_len):
    """Morceaux du bord supérieur de chaque fenêtre qui ne sont pas cachés par une fenêtre plus haute."""
    segs = []
    for i, w in enumerate(wins):
        r = w.rect
        if r.top() < min_top:
            continue
        parts = [(r.left() + 6.0, r.right() - 6.0)]
        for o in (x.rect for x in wins[:i]):
            if o.top() <= r.top() < o.bottom():
                parts = _subtract(parts, o.left(), o.right())
        for a, b in parts:
            if b - a >= min_len:
                segs.append(Seg(r.top(), a, b, w.hwnd))
    return segs


# --------------------------------------------------------------------------------------
#  États visuels
# --------------------------------------------------------------------------------------

PALETTE = {
    "idle": (0, 229, 255),
    "listening": (70, 140, 255),
    "thinking": (255, 176, 46),
    "speaking": (0, 255, 190),
    "alert": (255, 70, 80),
    "sleep": (90, 120, 160),
}


def lerp(a, b, t): return a + (b - a) * t
def clamp(v, a, b): return max(a, min(b, v))


@dataclass
class Bubble:
    text: str
    t0: float
    typed: float = 0.0
    life: float = 0.0


class Brain:
    """Comportement : vol, perchage sur les fenêtres, chute, lancer. Aucun dessin ici."""

    def __init__(self, desktop, bounds: QRect, S: float, use_windows=True):
        self.d, self.bounds, self.S, self.use_windows = desktop, bounds, S, use_windows
        self.R = 30.0 * S
        self.hover = 1.2 * self.R
        self.pos = QPointF(bounds.center().x(), bounds.center().y())
        self.vel = QPointF(0, 0)
        self.mode = "fly"            # fly | perch | fall | drag
        self.target = QPointF(self.pos)
        self.intent = "hover"        # hover | perch | cursor
        self.perch_hwnd = 0
        self.perch_dx = 0.0
        self.perch_t = 0.0
        self.walk_dir = 0.0
        self.dwell = 3.0
        self.look = QPointF(0, 0)
        self.squash = 0.0
        self.squash_t = 99.0
        self.excite = 0.0
        self.time = 0.0
        self.windows, self.segs = [], []
        self.poll_t = 0.0
        self.trail = deque(maxlen=22)
        self.follow_until = 0.0
        self.sleeping = False
        self.press = None
        self.drag_off = QPointF()
        self.drag_hist = deque(maxlen=6)
        self.hovering = False
        self.events = []             # ("poke",) ("land",) ("takeoff",) ("scan", hwnd)
        self.scan_next = 10.0
        self.refresh()

    # -- données du bureau
    def refresh(self):
        self.windows = self.d.windows() if self.use_windows else []
        self.segs = compute_platforms(self.windows, self.hover + 4, 110 * self.S) if self.use_windows else []

    def seg_for(self, hwnd, x):
        for s in self.segs:
            if s.hwnd == hwnd and s.x1 - self.R * 0.5 <= x <= s.x2 + self.R * 0.5:
                return s
        return None

    # -- ordres
    def goto(self, x, y):
        self.target, self.intent = QPointF(x, y), "hover"
        self._takeoff()

    def go_perch(self):
        if not self.segs:
            return self.go_roam()
        s = random.choice(self.segs)
        x = random.uniform(s.x1 + self.R, max(s.x1 + self.R, s.x2 - self.R))
        self.target = QPointF(x, s.y - self.hover)
        self.intent, self.perch_hwnd = "perch", s.hwnd
        self.perch_dx = x - (self.d.rect_of(s.hwnd) or QRect(0, 0, 0, 0)).left()
        self._takeoff()

    def go_roam(self):
        b = self.bounds
        self.target = QPointF(random.uniform(b.left() + 80, b.right() - 80),
                              random.uniform(b.top() + b.height() * 0.15, b.bottom() - b.height() * 0.25))
        self.intent = "hover"
        self._takeoff()

    def follow_cursor(self, secs=8.0):
        self.follow_until, self.intent = self.time + secs, "cursor"
        self._takeoff()

    def _takeoff(self):
        if self.mode == "perch":
            self.mode = "fly"
            self.vel = QPointF(random.uniform(-90, 90) * self.S, -420 * self.S)
            self.squash, self.squash_t = -0.5, 0.0
            self.events.append(("takeoff",))
        elif self.mode in ("fall", "drag"):
            self.mode = "fly"

    # -- boucle
    def update(self, dt):
        self.time += dt
        S, R = self.S, self.R
        cur = self.d.cursor()
        self.poll_t -= dt
        if self.poll_t <= 0:
            self.poll_t = 0.2
            self.refresh()
            if self.mode == "perch" and not self.seg_for(self.perch_hwnd, self.pos.x()):
                self.mode = "fall"                      # la fenêtre a bougé, été cachée ou fermée
                self.vel = QPointF(0, 40 * S)
        self._mouse(cur, dt)
        if self.mode == "drag":
            self._drag(cur, dt)
        elif self.mode == "perch":
            self._perch(dt)
        elif self.mode == "fall":
            self._fall(dt)
        else:
            self._fly(dt, cur)
        # regard : vers le curseur s'il est proche, sinon vers la direction du mouvement
        dx, dy = cur.x() - self.pos.x(), cur.y() - self.pos.y()
        dist = math.hypot(dx, dy)
        want = QPointF(0, 0)
        if dist < 650 * S and dist > 1:
            want = QPointF(dx / dist, dy / dist) * min(1.0, 200 * S / max(dist, 1) + 0.35)
        elif math.hypot(self.vel.x(), self.vel.y()) > 40 * S:
            sp = math.hypot(self.vel.x(), self.vel.y())
            want = QPointF(self.vel.x() / sp, self.vel.y() / sp) * 0.6
        k = 1 - math.exp(-9 * dt)
        self.look = QPointF(lerp(self.look.x(), want.x(), k), lerp(self.look.y(), want.y(), k))
        self.excite = lerp(self.excite, 1.0 if self.hovering else 0.0, 1 - math.exp(-6 * dt))
        self.squash_t += dt
        if self.mode != "drag" and math.hypot(self.vel.x(), self.vel.y()) > 60 * S:
            self.trail.append((self.pos.x(), self.pos.y(), self.time))
        elif self.trail and (self.time - self.trail[0][2] > 0.35):
            self.trail.popleft()

    def _mouse(self, cur, dt):
        down = self.d.mouse_down()
        near = math.hypot(cur.x() - self.pos.x(), cur.y() - self.pos.y()) < R_HIT * self.R
        self.hovering = near or self.mode == "drag"
        if down and self.press is None and near:
            self.press = (time.time(), QPointF(cur))
            self.drag_hist.clear()
        elif down and self.press is not None and self.mode != "drag":
            p0 = self.press[1]
            if math.hypot(cur.x() - p0.x(), cur.y() - p0.y()) > 8:
                self.mode = "drag"
                self.drag_off = self.pos - cur
        elif not down and self.press is not None:
            held = time.time() - self.press[0]
            if self.mode == "drag":
                v = QPointF(0, 0)
                if len(self.drag_hist) >= 2:
                    (t0, a), (t1, b) = self.drag_hist[0], self.drag_hist[-1]
                    if t1 > t0:
                        v = (b - a) / (t1 - t0)
                sp = math.hypot(v.x(), v.y())
                cap = 2600 * self.S
                if sp > cap:
                    v = v * (cap / sp)
                self.vel, self.mode = v, "fall"
            elif held < 0.5:
                self.events.append(("poke",))
                self.squash, self.squash_t = 0.6, 0.0
            self.press = None

    def _drag(self, cur, dt):
        tgt = cur + self.drag_off
        k = 1 - math.exp(-24 * dt)
        self.pos = self.pos + (tgt - self.pos) * k
        self.drag_hist.append((self.time, QPointF(self.pos)))

    def _fly(self, dt, cur):
        S = self.S
        if self.sleeping:
            floor = self.bounds.bottom() - 90 * S
            self.target = QPointF(self.pos.x(), min(self.pos.y(), floor)) if self.pos.y() > floor - 400 else QPointF(self.pos.x(), floor)
        elif self.intent == "cursor":
            if self.time > self.follow_until:
                self.intent = "hover"
            else:
                self.target = QPointF(cur.x() + 60 * S, cur.y() - 70 * S)
        d = self.target - self.pos
        dist = math.hypot(d.x(), d.y())
        maxv = (950 if self.intent != "cursor" else 1300) * S
        if dist > 1:
            desired = d * (min(maxv, dist * 4.2) / dist)
            # trajectoire en arc, plus vivante qu'une ligne droite
            perp = QPointF(-d.y() / dist, d.x() / dist) * (math.sin(self.time * 2.4) * min(dist, 500 * S) * 0.35)
            desired = desired + perp
            self.vel = self.vel + (desired - self.vel) * (1 - math.exp(-7 * dt))
        self.pos = self.pos + self.vel * dt
        if self.intent == "perch" and dist < 12 * S:
            r = self.d.rect_of(self.perch_hwnd)
            if r is not None and self.seg_for(self.perch_hwnd, self.pos.x()):
                self.mode, self.perch_t = "perch", 0.0
                self.dwell = random.uniform(7, 22)
                self.walk_dir = 0.0
                self.vel = QPointF(0, 0)
                self.squash, self.squash_t = 0.7, 0.0
                self.perch_dx = self.pos.x() - r.left()
                self.events.append(("land", self.perch_hwnd))
        elif self.intent == "hover" and dist < 14 * S and not self.sleeping:
            self.dwell -= dt
            if self.dwell <= 0:
                self._decide()
        # petit flottement quand il est en l'air
        self.pos = QPointF(self.pos.x(), self.pos.y() + math.sin(self.time * 2.1) * 8 * S * dt)

    def _decide(self):
        r = random.random()
        if r < 0.62 and self.segs:
            self.go_perch()
        elif r < 0.82:
            self.go_roam()
        else:
            self.follow_cursor(random.uniform(4, 8))
        self.dwell = random.uniform(2, 5)

    def _perch(self, dt):
        S = self.S
        r = self.d.rect_of(self.perch_hwnd)
        if r is None:
            self.mode, self.vel = "fall", QPointF(0, 30 * S)
            return
        self.perch_t += dt
        # il marche lentement le long du bord
        if self.walk_dir == 0.0 and random.random() < dt * 0.25:
            self.walk_dir = random.choice((-1.0, 1.0))
        elif self.walk_dir != 0.0 and random.random() < dt * 0.5:
            self.walk_dir = 0.0
        self.perch_dx += self.walk_dir * 38 * S * dt
        self.perch_dx = clamp(self.perch_dx, self.R + 10, max(self.R + 10, r.width() - self.R - 10))
        bob = math.sin(self.time * 2.6) * 2.5 * S
        self.pos = QPointF(r.left() + self.perch_dx, r.top() - self.hover + bob)
        self.vel = QPointF(0, 0)
        if not self.sleeping:
            self.dwell -= dt
            if self.dwell <= 0 or self.intent == "cursor":
                self.dwell = random.uniform(2, 5)
                self.intent = "hover"
                self._decide()
            elif self.time > self.scan_next:
                self.scan_next = self.time + random.uniform(14, 26)
                self.events.append(("scan", self.perch_hwnd))

    def _fall(self, dt):
        S = self.S
        g = 2300 * S
        prev_feet = self.pos.y() + self.hover
        self.vel = QPointF(self.vel.x() * (1 - 0.35 * dt), self.vel.y() + g * dt)
        self.pos = self.pos + self.vel * dt
        feet = self.pos.y() + self.hover
        b = self.bounds
        if self.pos.x() < b.left() + self.R:
            self.pos.setX(b.left() + self.R); self.vel.setX(abs(self.vel.x()) * 0.6)
        if self.pos.x() > b.right() - self.R:
            self.pos.setX(b.right() - self.R); self.vel.setX(-abs(self.vel.x()) * 0.6)
        if self.pos.y() < b.top() + self.R:
            self.pos.setY(b.top() + self.R); self.vel.setY(abs(self.vel.y()) * 0.5)
        if self.vel.y() > 0:
            for s in sorted(self.segs, key=lambda s: s.y):
                if prev_feet <= s.y + 2 <= feet and s.x1 <= self.pos.x() <= s.x2:
                    if self.vel.y() > 420 * S:
                        self.pos.setY(s.y - self.hover)
                        self.vel.setY(-self.vel.y() * 0.42)
                        self.squash, self.squash_t = 0.8, 0.0
                        self.events.append(("land", s.hwnd))
                        return
                    r = self.d.rect_of(s.hwnd)
                    if r is not None:
                        self.mode, self.perch_hwnd = "perch", s.hwnd
                        self.perch_dx, self.perch_t = self.pos.x() - r.left(), 0.0
                        self.dwell, self.walk_dir = random.uniform(6, 18), 0.0
                        self.vel = QPointF(0, 0)
                        self.squash, self.squash_t = 0.8, 0.0
                        self.events.append(("land", s.hwnd))
                        return
        floor = b.bottom() - self.hover - 4 * S
        if self.pos.y() >= floor:
            self.pos.setY(floor)
            if abs(self.vel.y()) > 380 * S:
                self.vel = QPointF(self.vel.x() * 0.8, -self.vel.y() * 0.42)
                self.squash, self.squash_t = 0.8, 0.0
            else:
                self.mode, self.intent, self.dwell = "fly", "hover", 0.5
                self.target = QPointF(self.pos.x(), self.pos.y() - 160 * S)

    def hit(self, p):
        return math.hypot(p.x() - self.pos.x(), p.y() - self.pos.y()) < R_HIT * self.R


R_HIT = 1.6


# --------------------------------------------------------------------------------------
#  Fenêtre transparente + dessin
# --------------------------------------------------------------------------------------


class Overlay(QWidget):
    def __init__(self, desktop, scale=1.0, use_windows=True, bounds=None):
        super().__init__()
        self.setWindowFlags(Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint | Qt.Tool |
                            Qt.WindowDoesNotAcceptFocus | Qt.NoDropShadowWindowHint)
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setAttribute(Qt.WA_ShowWithoutActivating)
        self.setAttribute(Qt.WA_TransparentForMouseEvents, False)
        self.setFocusPolicy(Qt.NoFocus)
        self.d = desktop
        screens = QGuiApplication.screens()
        if bounds is None:
            bounds = screens[0].geometry()
            for sc in screens[1:]:
                bounds = bounds.united(sc.geometry())
        self.bounds = bounds
        dpi = QGuiApplication.primaryScreen().logicalDotsPerInch() / 96.0 if QGuiApplication.primaryScreen() else 1.0
        self.S = clamp(dpi, 1.0, 3.0) * scale
        self.setGeometry(bounds)
        self.brain = Brain(desktop, bounds, self.S, use_windows)
        # état visuel
        self.state, self.state_t = "idle", 0.0
        self.color = list(map(float, PALETTE["idle"]))
        self.accent = None
        self.level = 0.0
        self.level_target = None
        self.bubble = None
        self.queue = []
        self.ripples = []            # (t0, force)
        self.fx_brackets = {}        # hwnd -> alpha
        self.scan = None             # (hwnd, t0)
        self.bracket_alpha = 0.0
        self.prev_rects = []
        self.passthrough = True
        self.hidden_for_fullscreen = False
        self.clock = QElapsedTimer()
        self.timer = QTimer(self)
        self.timer.setTimerType(Qt.PreciseTimer)
        self.timer.timeout.connect(self.tick)
        self.fs_t = 0.0
        self.top_t = 0.0
        self.font = QFont("Segoe UI")
        self.font.setPixelSize(int(15 * self.S))

    # -- cycle de vie
    def start(self):
        self.show()
        self.d.apply_style(self)
        self.clock.start()
        self.timer.start(16)

    def tick(self):
        dt = min(0.05, self.clock.restart() / 1000.0)
        self.step(dt)

    def step(self, dt):
        b = self.brain
        self.fs_t -= dt
        if self.fs_t <= 0:
            self.fs_t = 0.6
            fs = self.d.fullscreen_app()
            if fs != self.hidden_for_fullscreen:
                self.hidden_for_fullscreen = fs
                self.setVisible(not fs)
                if not fs:
                    self.d.apply_style(self)
        self.top_t -= dt
        if self.top_t <= 0:
            self.top_t = 2.0
            self.d.keep_on_top(self)
        b.update(dt)
        # réactions aux événements du comportement
        for ev in b.events:
            if ev[0] == "poke":
                self.ripples.append((time.time(), 1.0))
                self.say("Oui ?")
                self.set_state("listening", hold=5.0)
            elif ev[0] == "land":
                self.ripples.append((time.time(), 0.5))
                self.bracket_alpha = 0.0
            elif ev[0] == "scan":
                self.scan = (ev[1], b.time)
        b.events.clear()
        self.advance_visuals(dt)
        # clics : l'orbe n'est « solide » que quand la souris est dessus (sinon tout traverse)
        want = not b.hovering
        if want != self.passthrough:
            self.passthrough = want
            self.d.set_passthrough(self, want)
        self.invalidate()

    # -- états / commandes
    def set_state(self, name, hold=0.0):
        if name not in PALETTE:
            return
        self.state, self.state_t = name, 0.0
        self.hold = hold
        self.brain.sleeping = name == "sleep"
        if name != "sleep" and self.brain.mode == "fly" and self.brain.intent == "hover":
            self.brain.dwell = min(self.brain.dwell, 1.0)

    hold = 0.0

    def say(self, text):
        self.queue.append(text)

    def advance_visuals(self, dt):
        self.state_t += dt
        if self.hold > 0:
            self.hold -= dt
            if self.hold <= 0 and self.state in ("listening",):
                self.state = "idle"
        target = self.accent or PALETTE[self.state]
        k = 1 - math.exp(-8 * dt)
        for i in range(3):
            self.color[i] = lerp(self.color[i], target[i], k)
        t = self.brain.time
        # bulle de texte (machine à écrire)
        if self.bubble is None and self.queue:
            self.bubble = Bubble(self.queue.pop(0), t)
            self.prev_state = self.state
            if self.state in ("idle", "listening"):
                self.state, self.state_t = "speaking", 0.0
            self.speaking_from_bubble = True
        if self.bubble:
            bb = self.bubble
            bb.typed = min(len(bb.text), bb.typed + 42 * dt)
            if bb.typed >= len(bb.text):
                bb.life += dt
                if bb.life > 1.6 + 0.045 * len(bb.text):
                    self.bubble = None
                    if self.state == "speaking" and getattr(self, "speaking_from_bubble", False) and not self.queue:
                        self.state, self.state_t = "idle", 0.0
        # niveau audio simulé pendant la parole (ou fourni par la commande « level »)
        if self.state == "speaking":
            tgt = self.level_target if self.level_target is not None else 0.45 + 0.4 * math.sin(t * 9) * math.sin(t * 3.3)
        else:
            tgt = 0.0
        self.level = lerp(self.level, clamp(tgt, 0, 1), 1 - math.exp(-14 * dt))
        # accolades sur la fenêtre où il est posé
        want = 1.0 if (self.brain.mode == "perch" and self.state != "sleep") else 0.0
        self.bracket_alpha = lerp(self.bracket_alpha, want, 1 - math.exp(-4 * dt))
        self.ripples = [r for r in self.ripples if time.time() - r[0] < 1.6]
        if self.scan and t - self.scan[1] > 1.5:
            self.scan = None

    # -- rectangles à repeindre (on évite de redessiner tout l'écran)
    def visual_rects(self):
        b, R = self.brain, self.brain.R
        c = b.pos
        e = R * 3.6
        rs = [QRect(int(c.x() - e), int(c.y() - e), int(2 * e), int(2 * e))]
        if b.trail:
            xs = [p[0] for p in b.trail]
            ys = [p[1] for p in b.trail]
            rs.append(QRect(int(min(xs) - e), int(min(ys) - e), int(max(xs) - min(xs) + 2 * e), int(max(ys) - min(ys) + 2 * e)))
        if self.bubble:
            rs.append(self.bubble_rect().toAlignedRect().adjusted(-12, -12, 12, 12))
        if b.mode == "perch" and self.bracket_alpha > 0.01:
            r = self.d.rect_of(b.perch_hwnd)
            if r is not None:
                L = int(70 * self.S)
                for x, y in ((r.left(), r.top()), (r.right() - L, r.top()), (r.left(), r.bottom() - L), (r.right() - L, r.bottom() - L)):
                    rs.append(QRect(x - 8, y - 8, L + 16, L + 16))
                if self.scan:
                    rs.append(QRect(r.left() - 4, r.top() - 4, r.width() + 8, r.height() + 8))
        return rs

    def invalidate(self):
        new = self.visual_rects()
        reg = QRegion()
        for r in self.prev_rects + new:
            reg = reg.united(r.translated(-self.bounds.topLeft()).adjusted(-4, -4, 4, 4))
        self.prev_rects = new
        self.update(reg)

    def paintEvent(self, ev):
        p = QPainter(self)
        p.setCompositionMode(QPainter.CompositionMode_Source)
        p.fillRect(ev.rect(), Qt.transparent)
        p.setCompositionMode(QPainter.CompositionMode_SourceOver)
        self.paint_scene(p)

    def render_to(self, image):
        p = QPainter(image)
        self.paint_scene(p)
        p.end()

    # -- dessin
    def C(self, a=255, k=1.0):
        return QColor(int(clamp(self.color[0] * k, 0, 255)), int(clamp(self.color[1] * k, 0, 255)),
                      int(clamp(self.color[2] * k, 0, 255)), int(clamp(a, 0, 255)))

    def paint_scene(self, p):
        p.setRenderHint(QPainter.Antialiasing)
        p.translate(-self.bounds.topLeft())
        b = self.brain
        self.draw_window_fx(p)
        self.draw_trail(p)
        self.draw_orb(p)
        if self.bubble:
            self.draw_bubble(p)

    def draw_window_fx(self, p):
        b, S = self.brain, self.S
        if b.mode != "perch" or self.bracket_alpha < 0.01:
            return
        r = self.d.rect_of(b.perch_hwnd)
        if r is None:
            return
        a = self.bracket_alpha
        L = 26 * S
        pen = QPen(self.C(210 * a), 2.2 * S, Qt.SolidLine, Qt.SquareCap)
        p.setPen(pen)
        p.setBrush(Qt.NoBrush)
        x1, y1, x2, y2 = r.left() + 1, r.top() + 1, r.right() - 1, r.bottom() - 1
        for (x, y, sx, sy) in ((x1, y1, 1, 1), (x2, y1, -1, 1), (x1, y2, 1, -1), (x2, y2, -1, -1)):
            path = QPainterPath(QPointF(x + sx * L, y))
            path.lineTo(x, y)
            path.lineTo(x, y + sy * L)
            p.drawPath(path)
        # liseré lumineux sur le bord où il est posé
        g = QLinearGradient(r.left(), 0, r.right(), 0)
        g.setColorAt(0, self.C(0)); g.setColorAt(0.5, self.C(120 * a)); g.setColorAt(1, self.C(0))
        p.setPen(QPen(QBrush(g), 2.0 * S))
        p.drawLine(QPointF(r.left(), r.top()), QPointF(r.right(), r.top()))
        # balayage « scanner »
        if self.scan and self.scan[0] == b.perch_hwnd:
            u = clamp((b.time - self.scan[1]) / 1.5, 0, 1)
            y = r.top() + (r.height()) * (1 - (1 - u) ** 2)
            h = 46 * S
            g = QLinearGradient(0, y - h, 0, y)
            g.setColorAt(0, self.C(0)); g.setColorAt(1, self.C(70 * a))
            p.setPen(Qt.NoPen)
            p.setBrush(QBrush(g))
            p.drawRect(QRectF(r.left(), max(r.top(), y - h), r.width(), min(h, y - r.top())))
            p.setPen(QPen(self.C(230 * a), 1.6 * S))
            p.drawLine(QPointF(r.left(), y), QPointF(r.right(), y))

    def draw_trail(self, p):
        b, S = self.brain, self.S
        pts = list(b.trail)
        if len(pts) < 2:
            return
        p.setBrush(Qt.NoBrush)
        n = len(pts)
        for i in range(1, n):
            f = i / n
            pen = QPen(self.C(150 * f * f), b.R * 0.9 * f, Qt.SolidLine, Qt.RoundCap)
            p.setPen(pen)
            p.drawLine(QPointF(pts[i - 1][0], pts[i - 1][1]), QPointF(pts[i][0], pts[i][1]))

    def draw_orb(self, p):
        b, S, R = self.brain, self.S, self.brain.R
        t = b.time
        st = self.state
        sleepy = st == "sleep"
        c = QPointF(b.pos)
        # étirement selon la vitesse + rebond à l'atterrissage
        sp = math.hypot(b.vel.x(), b.vel.y())
        stretch = clamp(sp / (3200 * S), 0, 0.32)
        ang = math.degrees(math.atan2(b.vel.y(), b.vel.x())) if sp > 1 else 0
        sq = b.squash * math.exp(-6.5 * b.squash_t) * math.cos(17 * b.squash_t)
        breathe = 1 + 0.035 * math.sin(t * 2.3) + 0.05 * b.excite
        if sleepy:
            breathe *= 0.86
        # lueur posée sur le bord de la fenêtre
        if b.mode == "perch":
            pool_y = c.y() + b.hover + 2 * S
            g = QRadialGradient(QPointF(c.x(), pool_y), R * 2.3)
            g.setColorAt(0, self.C(110)); g.setColorAt(1, self.C(0))
            p.setPen(Qt.NoPen); p.setBrush(QBrush(g))
            p.save(); p.translate(c.x(), pool_y); p.scale(1, 0.16); p.translate(-c.x(), -pool_y)
            p.drawEllipse(QPointF(c.x(), pool_y), R * 2.3, R * 2.3)
            p.restore()
        p.save()
        p.translate(c)
        p.rotate(ang)
        p.scale(1 + stretch - sq * 0.22 * 0, 1 - stretch * 0.6)
        p.rotate(-ang)
        p.scale(1 + sq * 0.12, 1 - sq * 0.2)   # rebond vertical
        p.scale(breathe, breathe)
        alpha_k = 0.45 if sleepy else 1.0

        # halo
        halo_r = R * (3.0 + 0.5 * b.excite + 0.35 * self.level)
        g = QRadialGradient(QPointF(0, 0), halo_r)
        g.setColorAt(0, self.C(105 * alpha_k)); g.setColorAt(0.45, self.C(36 * alpha_k)); g.setColorAt(1, self.C(0))
        p.setPen(Qt.NoPen); p.setBrush(QBrush(g))
        p.drawEllipse(QPointF(0, 0), halo_r, halo_r)

        # ondes d'écoute / de clic
        now = time.time()
        for (t0, force) in self.ripples:
            u = (now - t0) / 1.6
            rr = R * (1.2 + 2.6 * u)
            p.setBrush(Qt.NoBrush)
            p.setPen(QPen(self.C(190 * (1 - u) * force), 2.0 * S * (1 - 0.6 * u)))
            p.drawEllipse(QPointF(0, 0), rr, rr)
        if st == "listening":
            for k in range(3):
                u = ((self.state_t * 0.7 + k / 3.0) % 1.0)
                p.setPen(QPen(self.C(170 * (1 - u)), 1.8 * S))
                p.setBrush(Qt.NoBrush)
                p.drawEllipse(QPointF(0, 0), R * (1.15 + 1.5 * u), R * (1.15 + 1.5 * u))

        # anneaux gyroscopiques
        spin = {"thinking": 3.2, "speaking": 1.6, "alert": 2.4, "sleep": 0.25}.get(st, 1.0) * (1 + 1.4 * b.excite)
        for i in range(3):
            tilt = 0.28 + 0.72 * abs(math.cos(t * (0.55 + 0.17 * i) + i * 1.9))
            p.save()
            p.rotate((t * 34 * spin * (1 if i % 2 == 0 else -1.3)) + i * 60)
            pen = QPen(self.C((200 - 30 * i) * alpha_k), (1.9 - 0.3 * i) * S)
            if i == 1:
                pen.setStyle(Qt.CustomDashLine); pen.setDashPattern([7, 4])
            if i == 2:
                pen.setStyle(Qt.CustomDashLine); pen.setDashPattern([1.2, 5])
                pen.setCapStyle(Qt.RoundCap)
            p.setPen(pen); p.setBrush(Qt.NoBrush)
            rr = R * (1.5 + 0.18 * i)
            p.drawEllipse(QPointF(0, 0), rr, rr * tilt)
            p.restore()

        # anneau de repères (HUD) + arc de « réflexion »
        ring = R * 2.05
        p.save()
        p.rotate(t * 9 * spin)
        for k in range(48):
            a = k * (360 / 48)
            long = k % 4 == 0
            p.save(); p.rotate(a)
            p.setPen(QPen(self.C((150 if long else 70) * alpha_k), 1.3 * S))
            p.drawLine(QPointF(ring, 0), QPointF(ring + (6.5 if long else 3.2) * S, 0))
            p.restore()
        p.restore()
        if st == "thinking":
            p.save(); p.rotate(t * 260)
            p.setPen(QPen(self.C(240), 3.2 * S, Qt.SolidLine, Qt.RoundCap)); p.setBrush(Qt.NoBrush)
            p.drawArc(QRectF(-ring - 8 * S, -ring - 8 * S, 2 * (ring + 8 * S), 2 * (ring + 8 * S)), 0, 70 * 16)
            p.setPen(QPen(self.C(120), 2.0 * S, Qt.SolidLine, Qt.RoundCap))
            p.drawArc(QRectF(-ring - 8 * S, -ring - 8 * S, 2 * (ring + 8 * S), 2 * (ring + 8 * S)), 180 * 16, 40 * 16)
            p.restore()
        if st == "alert":
            pulse = 0.5 + 0.5 * math.sin(t * 10)
            p.setBrush(Qt.NoBrush); p.setPen(QPen(self.C(120 + 120 * pulse), 2.4 * S))
            p.drawEllipse(QPointF(0, 0), R * (2.5 + 0.3 * pulse), R * (2.5 + 0.3 * pulse))

        # égaliseur circulaire pendant la parole
        if self.level > 0.02:
            n = 72
            base = R * 2.0
            for k in range(n):
                a = k * 2 * math.pi / n
                noise = 0.5 + 0.5 * math.sin(k * 1.7 + t * 8) * math.sin(k * 0.37 - t * 3.3)
                h = (3 + 22 * self.level * (0.25 + 0.75 * noise)) * S
                p.setPen(QPen(self.C(210), 2.0 * S, Qt.SolidLine, Qt.RoundCap))
                p.drawLine(QPointF(math.cos(a) * base, math.sin(a) * base), QPointF(math.cos(a) * (base + h), math.sin(a) * (base + h)))

        # particules en orbite
        p.setPen(Qt.NoPen)
        for k in range(14):
            sp_k = 0.5 + (k % 5) * 0.23
            a = t * sp_k * (1 if k % 2 else -1) + k * 1.9
            rr = R * (1.25 + (k % 6) * 0.17)
            z = 0.5 + 0.5 * math.sin(a * 1.0 + k)
            q = QPointF(math.cos(a) * rr, math.sin(a) * rr * (0.55 + 0.4 * ((k * 7) % 5) / 5))
            p.setBrush(self.C((90 + 150 * z) * alpha_k))
            p.drawEllipse(q, (1.2 + 1.7 * z) * S, (1.2 + 1.7 * z) * S)

        # cœur
        g = QRadialGradient(QPointF(0, 0), R)
        g.setColorAt(0.0, QColor(255, 255, 255, int(255 * alpha_k)))
        g.setColorAt(0.28, self.C(255 * alpha_k, 1.15))
        g.setColorAt(0.72, self.C(235 * alpha_k, 0.85))
        g.setColorAt(1.0, self.C(0))
        p.setPen(Qt.NoPen); p.setBrush(QBrush(g))
        p.drawEllipse(QPointF(0, 0), R, R)
        # iris sombre + pupille qui regarde la souris
        iris = R * 0.56
        g = QRadialGradient(QPointF(0, 0), iris)
        g.setColorAt(0, QColor(4, 10, 20, 235)); g.setColorAt(0.8, QColor(6, 16, 30, 215)); g.setColorAt(1, self.C(210))
        p.setBrush(QBrush(g))
        p.drawEllipse(QPointF(0, 0), iris, iris)
        look = QPointF(b.look.x() * iris * 0.42, b.look.y() * iris * 0.42)
        if sleepy:
            p.setPen(QPen(self.C(200), 2.0 * S, Qt.SolidLine, Qt.RoundCap))   # œil fermé
            p.drawLine(QPointF(-iris * 0.6, 0), QPointF(iris * 0.6, 0))
        else:
            blink = 0.12 if (int(t * 1.0) % 5 == 0 and (t % 1.0) < 0.12) else 1.0
            pr = iris * 0.52
            g = QRadialGradient(look, pr)
            g.setColorAt(0, QColor(255, 255, 255, 255)); g.setColorAt(0.5, self.C(255, 1.2)); g.setColorAt(1, self.C(0))
            p.setPen(Qt.NoPen); p.setBrush(QBrush(g))
            p.save(); p.translate(look); p.scale(1, blink); p.translate(-look)
            p.drawEllipse(look, pr, pr)
            p.restore()
        p.restore()

    def bubble_rect(self):
        b, S = self.brain, self.S
        fm = QFontMetricsF(self.font)
        text = self.bubble.text[:int(self.bubble.typed)] or " "
        full = self.bubble.text
        maxw = 360 * S
        r = fm.boundingRect(QRectF(0, 0, maxw, 2000), int(Qt.TextWordWrap), full)
        w, h = r.width() + 30 * S, r.height() + 22 * S
        c = b.pos
        x = c.x() + b.R * 2.4
        if x + w > self.bounds.right() - 10:
            x = c.x() - b.R * 2.4 - w
        y = c.y() - b.R * 2.6 - h * 0.5
        y = clamp(y, self.bounds.top() + 8, self.bounds.bottom() - h - 8)
        return QRectF(x, y, w, h)

    def draw_bubble(self, p):
        S = self.S
        r = self.bubble_rect()
        u = clamp(self.bubble.typed / 4.0, 0, 1)
        fade = 1.0
        full = len(self.bubble.text)
        if self.bubble.typed >= full:
            left = (1.6 + 0.045 * full) - self.bubble.life
            fade = clamp(left / 0.4, 0, 1)
        p.save()
        p.setOpacity(fade)
        path = QPainterPath()
        path.addRoundedRect(r, 16 * S, 16 * S)
        p.setPen(QPen(self.C(200), 1.4 * S))
        g = QLinearGradient(r.topLeft(), r.bottomLeft())
        g.setColorAt(0, QColor(10, 22, 38, 228)); g.setColorAt(1, QColor(5, 12, 24, 236))
        p.setBrush(QBrush(g))
        p.drawPath(path)
        # coins HUD
        p.setPen(QPen(self.C(255), 2.2 * S, Qt.SolidLine, Qt.SquareCap))
        L = 10 * S
        for (x, y, sx, sy) in ((r.left(), r.top(), 1, 1), (r.right(), r.bottom(), -1, -1)):
            pp = QPainterPath(QPointF(x + sx * L, y)); pp.lineTo(x, y); pp.lineTo(x, y + sy * L)
            p.drawPath(pp)
        p.setFont(self.font)
        p.setPen(QColor(222, 244, 255))
        shown = self.bubble.text[:int(self.bubble.typed)]
        p.drawText(r.adjusted(15 * S, 11 * S, -15 * S, -11 * S), int(Qt.TextWordWrap | Qt.AlignLeft | Qt.AlignTop), shown)
        p.restore()

    # -- commandes reçues (UDP local) ou du menu
    def command(self, line):
        parts = line.strip().split(None, 1)
        if not parts:
            return
        cmd, arg = parts[0].lower(), (parts[1] if len(parts) > 1 else "")
        b = self.brain
        try:
            if cmd == "state":
                self.set_state(arg.strip().lower())
            elif cmd == "say":
                self.say(arg)
            elif cmd == "level":
                v = float(arg)
                self.level_target = None if v < 0 else clamp(v, 0, 1)
            elif cmd == "goto":
                x, y = (float(v) for v in arg.split()[:2]); b.goto(x, y)
            elif cmd == "perch":
                b.go_perch()
            elif cmd == "roam":
                b.go_roam()
            elif cmd == "follow":
                b.follow_cursor(float(arg) if arg else 8.0)
            elif cmd == "color":
                c = QColor(arg.strip())
                self.accent = (c.red(), c.green(), c.blue()) if c.isValid() and arg.strip().lower() != "auto" else None
            elif cmd == "quit":
                QApplication.quit()
        except Exception as e:
            print("commande invalide:", line, e, file=sys.stderr)


def tray_icon():
    pm = QPixmap(64, 64)
    pm.fill(Qt.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.Antialiasing)
    g = QRadialGradient(QPointF(32, 32), 30)
    g.setColorAt(0, QColor(255, 255, 255)); g.setColorAt(0.35, QColor(0, 229, 255)); g.setColorAt(1, QColor(0, 120, 200, 0))
    p.setBrush(QBrush(g)); p.setPen(Qt.NoPen)
    p.drawEllipse(QPointF(32, 32), 30, 30)
    p.setPen(QPen(QColor(0, 229, 255), 3)); p.setBrush(Qt.NoBrush)
    p.drawEllipse(QPointF(32, 32), 24, 11)
    p.end()
    return QIcon(pm)


def main():
    ap = argparse.ArgumentParser(description="Jarvis : visuel de bureau")
    ap.add_argument("--taille", type=float, default=1.0, help="taille de l'orbe (1.0 = normal, 1.5 = plus gros)")
    ap.add_argument("--sans-fenetres", action="store_true", help="ne pas interagir avec les fenêtres")
    ap.add_argument("--port", type=int, default=8790, help="port UDP local des commandes")
    args = ap.parse_args()

    app = QApplication(sys.argv)
    app.setQuitOnLastWindowClosed(False)
    desktop = WinDesktop() if IS_WIN else FakeDesktop()
    ov = Overlay(desktop, scale=args.taille, use_windows=not args.sans_fenetres)

    sock = QUdpSocket()
    if not sock.bind(QHostAddress.LocalHost, args.port):
        print(f"Port {args.port} déjà utilisé : une autre instance tourne peut-être.", file=sys.stderr)
    def read():
        while sock.hasPendingDatagrams():
            data = sock.receiveDatagram().data().data().decode("utf-8", "ignore")
            for line in data.splitlines():
                ov.command(line)
    sock.readyRead.connect(read)

    tray = QSystemTrayIcon(tray_icon(), app)
    menu = QMenu()
    for label, st in (("Veille", "sleep"), ("Au repos", "idle"), ("Écoute", "listening"), ("Réfléchit", "thinking"),
                      ("Parle", "speaking"), ("Alerte", "alert")):
        menu.addAction(label, lambda s=st: ov.set_state(s))
    menu.addSeparator()
    menu.addAction("Se poser sur une fenêtre", ov.brain.go_perch)
    menu.addAction("Suivre la souris", lambda: ov.brain.follow_cursor(10))
    menu.addAction("Dire bonjour", lambda: ov.say("Bonjour. Tous les systèmes sont opérationnels."))
    menu.addSeparator()
    menu.addAction("Quitter", app.quit)
    tray.setContextMenu(menu)
    tray.setToolTip("Jarvis")
    tray.show()

    ov.start()
    ov.say("Systèmes en ligne.")
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
