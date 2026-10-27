"""Закруглённые элементы интерфейса для tkinter и иконка с Клодиком.

Фигуры рисуются через Pillow с увеличением в 3 раза и уменьшением —
так края получаются сглаженными (сам Canvas в Windows не сглаживает).
"""

import tkinter as tk
from functools import lru_cache

from PIL import Image, ImageDraw, ImageTk

# --- Палитра ---------------------------------------------------------------
BG = "#131318"          # фон окна
SIDEBAR = "#18181f"     # боковое меню
CARD = "#1e1e26"        # карточки
CARD_HI = "#262630"     # поля и кнопки на карточках
CARD_HOVER = "#2f2f3b"
BORDER = "#2a2a35"
TEXT = "#ececf1"
MUTED = "#8e8e9c"
ACCENT = "#d97757"      # оранжевый Клода
ACCENT_HOVER = "#e58a6b"
ACCENT_SOFT = "#3a2620"
GREEN = "#3ecf7f"
GREEN_SOFT = "#173527"
RED = "#f0616d"
RED_SOFT = "#3a1d22"
BLUE = "#6aa8ff"

FONT = "Segoe UI"
FONT_SEMI = "Segoe UI Semibold"
ICONS = "Segoe MDL2 Assets"

SCALE = 1.0  # задаётся из App по DPI


def px(v):
    return int(round(v * SCALE))


def _hex(c):
    c = c.lstrip("#")
    return tuple(int(c[i:i + 2], 16) for i in (0, 2, 4))


@lru_cache(maxsize=512)
def _rounded(w, h, r, fill, bg, outline=None):
    k = 3
    img = Image.new("RGB", (w * k, h * k), _hex(bg))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, w * k - 1, h * k - 1), radius=r * k, fill=_hex(fill),
                        outline=_hex(outline) if outline else None, width=k if outline else 0)
    return img.resize((w, h), Image.LANCZOS)


def rounded_photo(w, h, r, fill, bg, outline=None):
    return ImageTk.PhotoImage(_rounded(max(w, 2), max(h, 2), min(r, h // 2, w // 2), fill, bg, outline))


def parent_bg(widget):
    try:
        return widget.cget("bg")
    except tk.TclError:
        return BG


# --- Иконка с Клодиком -----------------------------------------------------
# Пиксельный Клодик: X — тело, K — глаза
CLAWD = [
    "..XXXXXXXXXX..",
    "..XXXXXXXXXX..",
    "..XXKXXXXKXX..",
    "XXXXKXXXXKXXXX",
    "XXXXXXXXXXXXXX",
    "..XXXXXXXXXX..",
    "..XXXXXXXXXX..",
    "..X.X....X.X..",
    "..X.X....X.X..",
]


def make_icon(size):
    """Квадрат со скруглёнными углами и оранжевым Клодиком внутри (RGBA)."""
    k = 4
    s = size * k
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # фон — тёплый тёмный градиент
    top, bottom = _hex("#2b2430"), _hex("#17151c")
    grad = Image.new("RGBA", (1, s))
    for y in range(s):
        t = y / (s - 1)
        grad.putpixel((0, y), tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,))
    grad = grad.resize((s, s))
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, s - 1, s - 1), radius=int(s * 0.23), fill=255)
    img.paste(grad, (0, 0), mask)

    cols, rows = len(CLAWD[0]), len(CLAWD)
    cell = s * 0.68 / cols
    ox = (s - cell * cols) / 2
    oy = (s - cell * rows) / 2 + s * 0.02
    body, eye = _hex(ACCENT) + (255,), _hex("#141216") + (255,)
    for ry, row in enumerate(CLAWD):
        for cx, ch in enumerate(row):
            if ch == ".":
                continue
            x0, y0 = ox + cx * cell, oy + ry * cell
            d.rectangle((x0, y0, x0 + cell + 0.5, y0 + cell + 0.5), fill=body if ch == "X" else eye)
    return img.resize((size, size), Image.LANCZOS)


def save_ico(path):
    make_icon(256).save(path, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])


# --- Виджеты ---------------------------------------------------------------

class Card(tk.Frame):
    """Карточка со скруглёнными углами. Содержимое кладите в .inner.

    Размер задаёт содержимое (.inner упакован с отступами), а скруглённый фон
    рисуется на холсте, растянутом под ним через place().
    """

    def __init__(self, master, title=None, padding=18, radius=14, fill=CARD):
        super().__init__(master, bg=parent_bg(master))
        self.fill, self.radius = fill, px(radius)
        self._bg = tk.Canvas(self, bg=parent_bg(master), highlightthickness=0, bd=0)
        self._bg.place(x=0, y=0, relwidth=1, relheight=1)
        self.inner = tk.Frame(self, bg=fill)
        self.inner.pack(fill="both", expand=True, padx=px(padding), pady=px(padding))
        self._img = None
        if title:
            tk.Label(self.inner, text=title.upper(), bg=fill, fg=MUTED,
                     font=(FONT_SEMI, 8)).pack(anchor="w", pady=(0, px(8)))
        self.bind("<Configure>", self._redraw)

    def _redraw(self, e):
        self._img = rounded_photo(e.width, e.height, self.radius, self.fill, parent_bg(self.master))
        self._bg.delete("all")
        self._bg.create_image(0, 0, anchor="nw", image=self._img)


class Button(tk.Canvas):
    STYLES = {
        "primary": (ACCENT, ACCENT_HOVER, "#ffffff"),
        "secondary": (CARD_HI, CARD_HOVER, TEXT),
        "danger": (RED_SOFT, "#4a242a", "#ff8a93"),
        "ghost": (CARD, CARD_HI, TEXT),
    }

    def __init__(self, master, text="", command=None, kind="secondary", height=38, width=None,
                 radius=10, font_size=10, icon=None):
        super().__init__(master, bg=parent_bg(master), highlightthickness=0, bd=0,
                         height=px(height), width=px(width or 120), cursor="hand2")
        self.text, self.command, self.kind, self.icon = text, command, kind, icon
        self.radius, self.font_size = px(radius), font_size
        self.enabled, self.hover = True, False
        self._img = None
        self.bind("<Configure>", lambda e: self._draw())
        self.bind("<Enter>", lambda e: self._set_hover(True))
        self.bind("<Leave>", lambda e: self._set_hover(False))
        self.bind("<ButtonRelease-1>", self._click)

    def _set_hover(self, v):
        self.hover = v
        self._draw()

    def _click(self, e):
        if self.enabled and self.command and 0 <= e.x <= self.winfo_width() and 0 <= e.y <= self.winfo_height():
            self.command()

    def _draw(self):
        w, h = self.winfo_width(), self.winfo_height()
        if w < 4:
            return
        fill, hover, fg = self.STYLES[self.kind]
        if not self.enabled:
            fill, fg = CARD_HI if self.kind != "ghost" else CARD, "#5c5c68"
        elif self.hover:
            fill = hover
        self._img = rounded_photo(w, h, self.radius, fill, parent_bg(self.master))
        self.delete("all")
        self.create_image(0, 0, anchor="nw", image=self._img)
        label = self.text
        weight = FONT_SEMI if self.kind == "primary" else FONT
        if self.icon:
            self.create_text(w / 2 - self._text_width(label) / 2 - px(6), h / 2, text=self.icon,
                             fill=fg, font=(ICONS, self.font_size))
            self.create_text(w / 2 + px(9), h / 2, text=label, fill=fg, font=(weight, self.font_size))
        else:
            self.create_text(w / 2, h / 2, text=label, fill=fg, font=(weight, self.font_size))
        self.configure(cursor="hand2" if self.enabled else "arrow")

    def _text_width(self, s):
        tid = self.create_text(0, 0, text=s, font=(FONT, self.font_size))
        x1, _, x2, _ = self.bbox(tid)
        self.delete(tid)
        return x2 - x1

    def configure(self, cnf=None, **kw):
        redraw = False
        for key in ("text", "command", "kind", "icon"):
            if key in kw:
                setattr(self, key, kw.pop(key))
                redraw = True
        if "state" in kw:
            self.enabled = kw.pop("state") != "disabled"
            redraw = True
        if cnf or kw:
            super().configure(cnf, **kw)
        if redraw:
            self._draw()

    config = configure


class Toggle(tk.Canvas):
    """Переключатель-«пилюля» как в Windows 11."""

    def __init__(self, master, variable=None, command=None):
        self.w, self.h = px(44), px(24)
        super().__init__(master, bg=parent_bg(master), highlightthickness=0, bd=0,
                         width=self.w, height=self.h, cursor="hand2")
        self.var = variable or tk.BooleanVar()
        self.command, self.enabled = command, True
        self._img = None
        self.var.trace_add("write", lambda *a: self._draw())
        self.bind("<ButtonRelease-1>", self._click)
        self._draw()

    def _click(self, _e):
        if not self.enabled:
            return
        self.var.set(not self.var.get())
        if self.command:
            self.command()

    def _draw(self):
        on = bool(self.var.get())
        fill = ACCENT if on else "#3a3a46"
        if not self.enabled:
            fill = "#5a3a30" if on else "#2a2a33"
        self._img = rounded_photo(self.w, self.h, self.h // 2, fill, parent_bg(self.master))
        self.delete("all")
        self.create_image(0, 0, anchor="nw", image=self._img)
        r = self.h // 2 - px(4)
        cx = self.w - self.h // 2 if on else self.h // 2
        self.create_oval(cx - r, self.h / 2 - r, cx + r, self.h / 2 + r, fill="#ffffff", outline="")

    def configure(self, cnf=None, **kw):
        if "state" in kw:
            self.enabled = kw.pop("state") != "disabled"
            self.configure(cursor="hand2" if self.enabled else "arrow")
            self._draw()
        if cnf or kw:
            super().configure(cnf, **kw)

    config = configure


class Dropdown(tk.Canvas):
    """Выпадающий список со скруглённым полем и тёмным списком."""

    def __init__(self, master, values=(), command=None, height=38, width=None):
        super().__init__(master, bg=parent_bg(master), highlightthickness=0, bd=0,
                         height=px(height), width=px(width or 200), cursor="hand2")
        self.values, self.command = list(values), command
        self.value, self.enabled, self.hover = "", True, False
        self.popup = None
        self._img = None
        self.bind("<Configure>", lambda e: self._draw())
        self.bind("<Enter>", lambda e: self._set_hover(True))
        self.bind("<Leave>", lambda e: self._set_hover(False))
        self.bind("<ButtonRelease-1>", lambda e: self.open() if self.enabled else None)

    def _set_hover(self, v):
        self.hover = v
        self._draw()

    def _draw(self):
        w, h = self.winfo_width(), self.winfo_height()
        if w < 4:
            return
        fill = CARD_HOVER if self.hover and self.enabled else CARD_HI
        self._img = rounded_photo(w, h, px(10), fill, parent_bg(self.master))
        self.delete("all")
        self.create_image(0, 0, anchor="nw", image=self._img)
        fg = TEXT if self.enabled else "#5c5c68"
        self.create_text(px(14), h / 2, text=self.value, fill=fg, font=(FONT, 10), anchor="w")
        self.create_text(w - px(18), h / 2 + px(1), text="", fill=MUTED, font=(ICONS, 8))

    def get(self):
        return self.value

    def set(self, v):
        self.value = v
        self._draw()

    def current(self):
        return self.values.index(self.value) if self.value in self.values else -1

    def open(self):
        if self.popup or not self.values:
            return
        top = self.popup = tk.Toplevel(self)
        top.overrideredirect(True)
        top.attributes("-topmost", True)
        top.configure(bg=BORDER)
        rows = min(len(self.values), 12)
        lb = tk.Listbox(top, bg=CARD_HI, fg=TEXT, selectbackground=ACCENT, selectforeground="#ffffff",
                        activestyle="none", highlightthickness=0, bd=0, font=(FONT, 10), height=rows,
                        relief="flat")
        for v in self.values:
            lb.insert("end", "  " + v)
        lb.pack(fill="both", expand=True, padx=1, pady=1)
        if self.value in self.values:
            i = self.values.index(self.value)
            lb.selection_set(i)
            lb.see(i)
        x, y = self.winfo_rootx(), self.winfo_rooty() + self.winfo_height() + px(4)
        top.geometry(f"{self.winfo_width()}x{lb.winfo_reqheight() + 2}+{x}+{y}")
        lb.bind("<Motion>", lambda e: (lb.selection_clear(0, "end"), lb.selection_set(lb.nearest(e.y))))
        lb.bind("<ButtonRelease-1>", lambda e: self._pick(lb.nearest(e.y)))
        lb.bind("<Return>", lambda e: self._pick(lb.curselection()[0] if lb.curselection() else -1))
        top.bind("<Escape>", lambda e: self.close())
        top.bind("<FocusOut>", lambda e: self.after(50, self.close))
        top.focus_force()
        lb.focus_set()

    def _pick(self, i):
        self.close()
        if 0 <= i < len(self.values) and self.values[i] != self.value:
            self.set(self.values[i])
            if self.command:
                self.command()

    def close(self):
        if self.popup:
            self.popup.destroy()
            self.popup = None

    def configure(self, cnf=None, **kw):
        if "values" in kw:
            self.values = list(kw.pop("values"))
        if "state" in kw:
            self.enabled = kw.pop("state") != "disabled"
        if cnf or kw:
            super().configure(cnf, **kw)
        self._draw()

    config = configure


class Pill(tk.Canvas):
    """Плашка статуса: «● Работает»."""

    def __init__(self, master, height=30):
        super().__init__(master, bg=parent_bg(master), highlightthickness=0, bd=0, height=px(height), width=px(120))
        self._img = None

    def set(self, text, fg, fill):
        f = (FONT_SEMI, 9)
        tid = self.create_text(0, 0, text=text, font=f)
        x1, _, x2, _ = self.bbox(tid)
        self.delete("all")
        h = int(self.cget("height"))
        w = x2 - x1 + px(38)
        self.configure(width=w)
        self._img = rounded_photo(w, h, h // 2, fill, parent_bg(self.master))
        self.create_image(0, 0, anchor="nw", image=self._img)
        self.create_oval(px(13), h / 2 - px(4), px(21), h / 2 + px(4), fill=fg, outline="")
        self.create_text(px(28), h / 2, text=text, fill=fg, font=f, anchor="w")


class Progress(tk.Canvas):
    """Скруглённая полоса прогресса."""

    def __init__(self, master, height=8):
        super().__init__(master, bg=parent_bg(master), highlightthickness=0, bd=0, height=px(height))
        self.value = 0.0
        self._imgs = []
        self.bind("<Configure>", lambda e: self._draw())

    def set(self, value):
        self.value = max(0.0, min(1.0, value))
        self._draw()

    def _draw(self):
        w, h = self.winfo_width(), self.winfo_height()
        if w < 4:
            return
        self.delete("all")
        track = rounded_photo(w, h, h // 2, CARD_HI, parent_bg(self.master))
        self._imgs = [track]
        self.create_image(0, 0, anchor="nw", image=track)
        fw = int(w * self.value)
        if fw >= h:
            bar = rounded_photo(fw, h, h // 2, ACCENT, CARD_HI)
            self._imgs.append(bar)
            self.create_image(0, 0, anchor="nw", image=bar)


class NavItem(tk.Canvas):
    """Пункт бокового меню."""

    def __init__(self, master, icon, text, command):
        super().__init__(master, bg=parent_bg(master), highlightthickness=0, bd=0, height=px(40), cursor="hand2")
        self.icon, self.text, self.command = icon, text, command
        self.active = self.hover = False
        self._img = None
        self.bind("<Configure>", lambda e: self._draw())
        self.bind("<Enter>", lambda e: self._set(hover=True))
        self.bind("<Leave>", lambda e: self._set(hover=False))
        self.bind("<ButtonRelease-1>", lambda e: self.command())

    def _set(self, **kw):
        for k, v in kw.items():
            setattr(self, k, v)
        self._draw()

    def _draw(self):
        w, h = self.winfo_width(), self.winfo_height()
        if w < 4:
            return
        self.delete("all")
        if self.active or self.hover:
            fill = ACCENT_SOFT if self.active else CARD
            self._img = rounded_photo(w, h, px(10), fill, parent_bg(self.master))
            self.create_image(0, 0, anchor="nw", image=self._img)
        if self.active:
            self.create_rectangle(0, h / 2 - px(9), px(3), h / 2 + px(9), fill=ACCENT, outline="")
        fg = ACCENT if self.active else (TEXT if self.hover else MUTED)
        self.create_text(px(20), h / 2, text=self.icon, fill=fg, font=(ICONS, 11))
        self.create_text(px(42), h / 2, text=self.text, fill=TEXT if self.active else fg,
                         font=(FONT_SEMI if self.active else FONT, 10), anchor="w")
