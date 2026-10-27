"""Окно «Запрет от Фузика»: боковое меню, карточки, тёмная тема."""

import ctypes
import os
import queue
import re
import subprocess
import threading
import time
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox

from PIL import ImageTk

import ui_kit as ui
from ui_kit import (ACCENT, BG, BLUE, CARD, CARD_HI, FONT, FONT_SEMI, GREEN, GREEN_SOFT, MUTED, RED,
                    RED_SOFT, SIDEBAR, TEXT, Button, Card, Dropdown, NavItem, Pill, Progress, Toggle, px)
from zapret_fuzik import (APP_NAME, CHECK_TARGETS, DEFAULT_DIR, GAME_MODES, IPSET_MODES, PROBE_TEXT, SERVICE,
                          TgProxy, Zapret, check_services, download_and_install, find_best_strategy, is_newer,
                          latest_release, latest_tg_release, load_config, run, save_config)

ICON_HOME, ICON_TG, ICON_SETTINGS, ICON_TOOLS, ICON_CHECK = "", "", "", "", ""
ICON_FOLDER, ICON_EDIT, ICON_SYNC, ICON_TEST, ICON_LINK = "", "", "", "", ""


def label(parent, text="", size=10, color=TEXT, bold=False, **kw):
    return tk.Label(parent, text=text, bg=parent.cget("bg"), fg=color,
                    font=(FONT_SEMI if bold else FONT, size), anchor="w", justify="left", **kw)


def dark_titlebar(win):
    """Тёмный заголовок окна и скругление углов (Windows 10/11)."""
    try:
        hwnd = ctypes.windll.user32.GetParent(win.winfo_id())
        dwm = ctypes.windll.dwmapi
        on = ctypes.c_int(1)
        dwm.DwmSetWindowAttribute(hwnd, 20, ctypes.byref(on), 4)           # тёмный режим
        r, g, b = (int(BG[i:i + 2], 16) for i in (1, 3, 5))
        color = ctypes.c_int(r | (g << 8) | (b << 16))
        dwm.DwmSetWindowAttribute(hwnd, 35, ctypes.byref(color), 4)        # цвет заголовка (Win11)
        corner = ctypes.c_int(2)
        dwm.DwmSetWindowAttribute(hwnd, 33, ctypes.byref(corner), 4)       # скруглённые углы (Win11)
    except (OSError, AttributeError):
        pass


class App(tk.Tk):
    W, H = 880, 600

    def __init__(self):
        super().__init__()
        ui.SCALE = self.winfo_fpixels("1i") / 96
        self.title(APP_NAME)
        self.configure(bg=BG)
        self.resizable(False, False)
        self.geometry(f"{px(self.W)}x{px(self.H)}")
        self._icons = [ImageTk.PhotoImage(ui.make_icon(s)) for s in (64, 32)]
        self.iconphoto(True, *self._icons)
        self.update_idletasks()
        dark_titlebar(self)

        self.cfg = load_config()
        self.z = self.tg = None
        self.busy = False
        self.pending_release = self.pending_tg = None
        self.events = queue.Queue()
        self.controls = []
        self.polling = False

        self.root_frame = tk.Frame(self, bg=BG)
        self.root_frame.pack(fill="both", expand=True)
        self.after(100, self.pump_events)

        root = self.cfg.get("install_dir")
        if Zapret.is_installed(root):
            self.open_install(root)
        else:
            self.show_setup()

    # ------------------------------------------------------------------
    # Фоновые задачи
    # ------------------------------------------------------------------
    def task(self, title, fn, done=None):
        if self.busy:
            return
        self.busy = True
        self.set_controls_state("disabled")
        self.configure(cursor="watch")

        def worker():
            try:
                self.events.put(("done", done, fn()))
            except Exception as e:  # noqa: BLE001 — показываем любую ошибку пользователю
                self.events.put(("error", title, str(e)))

        threading.Thread(target=worker, daemon=True).start()

    def log(self, msg):
        self.events.put(("log", msg))

    def pump_events(self):
        try:
            while True:
                ev = self.events.get_nowait()
                if ev[0] == "log":
                    self.write_log(ev[1])
                    continue
                if ev[0] == "ui":
                    ev[1]()
                    continue
                self.busy = False
                self.configure(cursor="")
                self.set_controls_state("normal")
                if ev[0] == "error":
                    self.write_log(f"Ошибка: {ev[2]}")
                    messagebox.showerror(ev[1], ev[2], parent=self)
                elif ev[1]:
                    ev[1](ev[2])
                self.refresh()
        except queue.Empty:
            pass
        self.after(100, self.pump_events)

    def write_log(self, msg):
        line = f"[{time.strftime('%H:%M:%S')}] {msg}"
        if getattr(self, "txt_log", None) and self.txt_log.winfo_exists():
            self.txt_log.configure(state="normal")
            self.txt_log.insert("end", line + "\n")
            self.txt_log.see("end")
            self.txt_log.configure(state="disabled")
        if getattr(self, "lbl_last", None) and self.lbl_last.winfo_exists():
            self.lbl_last.configure(text=msg)

    def set_controls_state(self, state):
        for w in self.controls:
            try:
                w.configure(state=state)
            except tk.TclError:
                pass

    def clear(self):
        for w in self.root_frame.winfo_children():
            w.destroy()
        self.controls = []
        self.txt_log = self.lbl_last = None

    # ------------------------------------------------------------------
    # Экран установки (первый запуск / новый компьютер)
    # ------------------------------------------------------------------
    def show_setup(self, can_cancel=False):
        self.clear()
        wrap = tk.Frame(self.root_frame, bg=BG)
        wrap.place(relx=0.5, rely=0.5, anchor="center", width=px(560))

        self._setup_icon = ImageTk.PhotoImage(ui.make_icon(px(76)))
        tk.Label(wrap, image=self._setup_icon, bg=BG).pack(pady=(0, px(12)))
        label(wrap, APP_NAME, 22, bold=True).pack()
        label(wrap, "Выберите папку — программа скачает туда последнюю версию zapret с GitHub",
              10, MUTED).pack(pady=(px(4), px(18)))

        card = Card(wrap)
        card.pack(fill="x")
        c = card.inner
        label(c, "Папка установки", 9, MUTED).pack(anchor="w")
        row = tk.Frame(c, bg=CARD)
        row.pack(fill="x", pady=(px(6), px(4)))
        field = Card(row, padding=9, radius=10, fill=CARD_HI)
        field.pack(side="left", fill="x", expand=True)
        self.var_dir = tk.StringVar(value=self.cfg.get("install_dir") or DEFAULT_DIR)
        ent = tk.Entry(field.inner, textvariable=self.var_dir, bg=CARD_HI, fg=TEXT, insertbackground=TEXT,
                       relief="flat", font=(FONT, 10), highlightthickness=0, bd=0)
        ent.pack(fill="x")
        b_browse = Button(row, "Обзор", self.browse_dir, width=96, icon=ICON_FOLDER)
        b_browse.pack(side="left", padx=(px(8), 0), fill="y")
        label(c, "Без русских букв в пути, например C:\\zapret", 9, MUTED).pack(anchor="w")

        row = tk.Frame(c, bg=CARD)
        row.pack(fill="x", pady=(px(14), 0))
        self.var_with_tg = tk.BooleanVar(value=True)
        tg_toggle = Toggle(row, self.var_with_tg)
        tg_toggle.pack(side="right")
        label(row, "Ускорение Telegram", 10, bold=True).pack(anchor="w")
        label(row, "TG WS Proxy, около 20 МБ", 9, MUTED).pack(anchor="w")

        b_install = Button(c, "Скачать и установить", self.do_install, kind="primary", height=44)
        b_install.pack(fill="x", pady=(px(18), px(6)))
        b_existing = Button(c, "У меня уже есть папка zapret", self.pick_existing, kind="ghost", height=34)
        b_existing.pack(fill="x")
        if can_cancel:
            Button(c, "Назад", lambda: self.open_install(self.cfg["install_dir"]), kind="ghost",
                   height=34).pack(fill="x")

        self.lbl_last = label(wrap, "", 9, MUTED)
        self.lbl_last.pack(pady=(px(12), 0))
        self.controls = [ent, b_browse, b_install, b_existing, tg_toggle]

    def browse_dir(self):
        d = filedialog.askdirectory(parent=self, title="Куда установить zapret", initialdir="C:\\")
        if d:
            path = Path(d)
            if path.exists() and any(path.iterdir()) and not Zapret.is_installed(path):
                path = path / "zapret"
            self.var_dir.set(str(path))

    def pick_existing(self):
        d = filedialog.askdirectory(parent=self, title="Папка с zapret (где лежит service.bat)")
        if not d:
            return
        if not Zapret.is_installed(d):
            messagebox.showerror(APP_NAME, "В этой папке нет zapret (не найдены service.bat и bin\\winws.exe).",
                                 parent=self)
            return
        self.cfg["install_dir"] = str(Path(d))
        save_config(self.cfg)
        self.open_install(d)

    def do_install(self):
        target = Path(self.var_dir.get().strip())
        if not target.is_absolute():
            messagebox.showerror(APP_NAME, "Укажите полный путь, например C:\\zapret", parent=self)
            return
        if re.search(r"[^\x00-\x7F]", str(target)):
            if not messagebox.askyesno(APP_NAME, "В пути есть русские буквы или спецсимволы — zapret может "
                                                 "не работать.\n\nВсё равно установить сюда?", parent=self):
                return
        with_tg = self.var_with_tg.get()

        def job():
            self.log("Узнаю последнюю версию на GitHub...")
            rel = latest_release()
            download_and_install(target, rel, self.log)
            if with_tg:
                try:
                    TgProxy(target).install(latest_tg_release(), self.log)
                except Exception as e:  # noqa: BLE001 — zapret уже стоит, Telegram можно доставить позже
                    self.log(f"Не удалось установить TG WS Proxy: {e}")
            return str(target)

        def done(path):
            self.cfg["install_dir"] = path
            save_config(self.cfg)
            Zapret(path).updates_flag.write_text("ENABLED\r\n", "ascii")
            self.open_install(path)
            self.write_log(f"zapret установлен в {path}")

        self.task("Установка", job, done)

    # ------------------------------------------------------------------
    # Главное окно
    # ------------------------------------------------------------------
    def open_install(self, root):
        self.z = Zapret(root)
        self.tg = TgProxy(root)
        self.z.ensure_user_files()
        self.show_main()
        if self.z.updates_flag.exists():
            self.check_updates(silent=True)

    def show_main(self):
        self.clear()
        side = tk.Frame(self.root_frame, bg=SIDEBAR, width=px(214))
        side.pack(side="left", fill="y")
        side.pack_propagate(False)
        content = tk.Frame(self.root_frame, bg=BG)
        content.pack(side="left", fill="both", expand=True, padx=px(26), pady=px(22))

        # --- боковое меню ---
        head = tk.Frame(side, bg=SIDEBAR)
        head.pack(fill="x", padx=px(16), pady=(px(20), px(22)))
        self._side_icon = ImageTk.PhotoImage(ui.make_icon(px(40)))
        tk.Label(head, image=self._side_icon, bg=SIDEBAR).pack(side="left")
        t = tk.Frame(head, bg=SIDEBAR)
        t.pack(side="left", padx=(px(10), 0))
        label(t, "Запрет", 13, bold=True).pack(anchor="w")
        label(t, "от Фузика", 9, ACCENT).pack(anchor="w")

        self.pages, self.nav = {}, {}
        for key, icon, text in (("home", ICON_HOME, "Главная"), ("check", ICON_CHECK, "Проверка"),
                                ("tg", ICON_TG, "Telegram"),
                                ("settings", ICON_SETTINGS, "Настройки"), ("tools", ICON_TOOLS, "Инструменты")):
            item = NavItem(side, icon, text, lambda k=key: self.show_page(k))
            item.pack(fill="x", padx=px(10), pady=px(2))
            self.nav[key] = item
            self.pages[key] = tk.Frame(content, bg=BG)

        foot = tk.Frame(side, bg=SIDEBAR)
        foot.pack(side="bottom", fill="x", padx=px(18), pady=px(18))
        self.lbl_side_ver = label(foot, "", 9, MUTED)
        self.lbl_side_ver.pack(anchor="w")
        self.btn_side_update = Button(foot, "Обновить", self.do_update, kind="primary", height=32, icon=ICON_SYNC)

        self.build_home(self.pages["home"])
        self.build_check(self.pages["check"])
        self.build_tg(self.pages["tg"])
        self.build_settings(self.pages["settings"])
        self.build_tools(self.pages["tools"])
        self.show_page("home")

        self.fill_strategies()
        self.update_versions()
        self.write_log(f"Папка: {self.z.root}")
        self.refresh()
        if not self.polling:
            self.polling = True
            self.after(3000, self.poll)

    def show_page(self, key):
        for k, p in self.pages.items():
            p.pack_forget()
            self.nav[k]._set(active=(k == key))
        self.pages[key].pack(fill="both", expand=True)

    def page_header(self, page, title, subtitle=None):
        row = tk.Frame(page, bg=BG)
        row.pack(fill="x", pady=(0, px(16)))
        pill = Pill(row)
        pill.pack(side="right", anchor="n", pady=(px(4), 0))
        label(row, title, 20, bold=True).pack(anchor="w")
        if subtitle:
            label(row, subtitle, 10, MUTED).pack(anchor="w")
        return pill

    # --- Главная ---
    def build_home(self, p):
        self.pill_home = self.page_header(p, "Главная", "Обход блокировок Discord, YouTube и других сайтов")

        hero = Card(p, padding=20)
        hero.pack(fill="x")
        h = hero.inner
        self.lbl_status = label(h, "", 17, bold=True)
        self.lbl_status.pack(anchor="w")
        self.lbl_detail = label(h, "", 10, MUTED, wraplength=px(560))
        self.lbl_detail.pack(anchor="w", pady=(px(2), px(14)))
        label(h, "Стратегия", 9, MUTED).pack(anchor="w", pady=(0, px(6)))
        self.dd_strategy = Dropdown(h)
        self.dd_strategy.pack(fill="x")
        row = tk.Frame(h, bg=CARD)
        row.pack(fill="x", pady=(px(14), 0))
        self.btn_start = Button(row, "Запустить", self.on_start, kind="primary", height=46, font_size=11)
        self.btn_start.pack(side="left", fill="x", expand=True)
        self.btn_stop = Button(row, "Остановить", self.on_stop, height=46, width=160, font_size=11)
        self.btn_stop.pack(side="left", padx=(px(10), 0))

        row = tk.Frame(p, bg=BG)
        row.pack(fill="x", pady=(px(14), 0))
        row.columnconfigure(0, weight=1, uniform="c")
        row.columnconfigure(1, weight=1, uniform="c")

        c1 = Card(row, "Автозапуск")
        c1.grid(row=0, column=0, sticky="nsew", padx=(0, px(7)))
        r = tk.Frame(c1.inner, bg=CARD)
        r.pack(fill="x")
        self.var_svc = tk.BooleanVar()
        self.tgl_svc = Toggle(r, self.var_svc, self.on_service_toggle)
        self.tgl_svc.pack(side="right")
        label(r, "Вместе с Windows", 10, bold=True).pack(anchor="w")
        self.lbl_svc = label(r, "", 9, MUTED)
        self.lbl_svc.pack(anchor="w")

        c2 = Card(row, "Telegram")
        c2.grid(row=0, column=1, sticky="nsew", padx=(px(7), 0))
        self.lbl_tg_home = label(c2.inner, "", 10, bold=True)
        self.lbl_tg_home.pack(anchor="w")
        r = tk.Frame(c2.inner, bg=CARD)
        r.pack(fill="x", pady=(px(8), 0))
        self.btn_tg_home = Button(r, "", self.on_tg_run, height=32)
        self.btn_tg_home.pack(side="left", fill="x", expand=True)
        self.btn_tg_home_more = Button(r, "Подробнее", lambda: self.show_page("tg"), kind="ghost", height=32,
                                       width=100)
        self.btn_tg_home_more.pack(side="left", padx=(px(6), 0))

        self.lbl_last = label(p, "", 9, MUTED)
        self.lbl_last.pack(side="bottom", anchor="w")
        self.controls += [self.dd_strategy, self.btn_start, self.btn_stop, self.tgl_svc, self.btn_tg_home]

    # --- Проверка ---
    def build_check(self, p):
        self.page_header(p, "Проверка", "Открываются ли сайты прямо сейчас и какая стратегия лучше")

        card = Card(p, padding=20)
        card.pack(fill="x")
        c = card.inner
        top = tk.Frame(c, bg=CARD)
        top.pack(fill="x")
        self.btn_check = Button(top, "Проверить сейчас", self.on_check, kind="primary", width=190, height=38,
                                icon=ICON_CHECK)
        self.btn_check.pack(side="right")
        label(top, "Доступность сайтов", 12, bold=True).pack(anchor="w")
        self.lbl_check_time = label(top, "Нажмите «Проверить сейчас»", 9, MUTED)
        self.lbl_check_time.pack(anchor="w")

        self.check_rows = {}
        for name in CHECK_TARGETS:
            r = tk.Frame(c, bg=CARD)
            r.pack(fill="x", pady=(px(10), 0))
            dot = tk.Canvas(r, width=px(10), height=px(10), bg=CARD, highlightthickness=0)
            dot.pack(side="left", padx=(0, px(10)))
            dot.create_oval(1, 1, px(9), px(9), fill=CARD_HI, outline="", tags="dot")
            label(r, name, 10, bold=True).pack(side="left")
            val = label(r, "—", 10, MUTED)
            val.pack(side="right")
            self.check_rows[name] = (dot, val)

        card = Card(p, padding=20)
        card.pack(fill="x", pady=(px(14), 0))
        c = card.inner
        top = tk.Frame(c, bg=CARD)
        top.pack(fill="x")
        self.btn_best = Button(top, "Подобрать автоматически", self.on_find_best, width=220, height=38,
                               icon=ICON_TEST)
        self.btn_best.pack(side="right")
        label(top, "Подбор стратегии", 12, bold=True).pack(anchor="w")
        self.lbl_best = label(top, "Перепробует все стратегии (2–3 минуты) и найдёт лучшую", 9, MUTED)
        self.lbl_best.pack(anchor="w")
        self.progress = Progress(c)
        self.best_box = tk.Frame(c, bg=CARD)
        self.best_box.pack(fill="x")
        self.cancel_search = threading.Event()
        self.controls += [self.btn_check, self.btn_best]

    def set_dot(self, name, color, text, fg):
        dot, val = self.check_rows[name]
        dot.itemconfigure("dot", fill=color)
        val.configure(text=text, fg=fg)

    def on_check(self):
        for name in CHECK_TARGETS:
            self.set_dot(name, CARD_HI, "проверяю...", MUTED)
        self.lbl_check_time.configure(text="Проверяю...")

        def done(results):
            for name, rows in results.items():
                ok = [ms for _, st, ms in rows if st == "ok"]
                if len(ok) == len(rows):
                    self.set_dot(name, GREEN, f"работает · {sum(ok) // len(ok)} мс", GREEN)
                elif ok:
                    bad = [h for h, st, _ in rows if st != "ok"]
                    self.set_dot(name, ACCENT, f"частично: {len(ok)} из {len(rows)} (не открылся {bad[0]})", ACCENT)
                else:
                    reason = PROBE_TEXT[rows[0][1]]
                    self.set_dot(name, RED, f"не открывается — {reason}", RED)
            mode = "с обходом" if self.z.winws_running() else "без обхода"
            self.lbl_check_time.configure(text=f"Проверено в {time.strftime('%H:%M')} · {mode}")
            self.write_log("Проверка подключения завершена")

        self.task("Проверка", check_services, done)

    def on_find_best(self):
        if not messagebox.askyesno(APP_NAME, "Программа по очереди включит все стратегии и проверит, открываются ли "
                                             "Discord, YouTube и Google. Это займёт 2–3 минуты, интернет на это "
                                             "время может подтормаживать.\n\nНачать?", parent=self):
            return
        for w in self.best_box.winfo_children():
            w.destroy()
        self.progress.pack(fill="x", pady=(px(12), 0), before=self.best_box)
        self.progress.set(0)
        self.cancel_search.clear()
        self.btn_best.configure(text="Остановить подбор", command=self.cancel_search.set, kind="danger")

        svc_running = self.z.service_state() == "RUNNING"
        was_running = self.z.winws_running() and not svc_running
        last = self.cfg.get("last_strategy", "general")

        def progress(i, n, name):
            self.events.put(("ui", lambda: (self.progress.set(i / n),
                                            self.lbl_best.configure(text=f"Проверяю {i + 1} из {n}: {name}"))))

        def job():
            if svc_running:
                run(["sc", "stop", SERVICE])
                time.sleep(1)
            try:
                return find_best_strategy(self.z, progress, self.cancel_search)
            finally:
                if svc_running:
                    run(["sc", "start", SERVICE])
                elif was_running:
                    self.z.start(last)

        def done(results):
            self.btn_best.configure(text="Подобрать автоматически", command=self.on_find_best, kind="secondary")
            self.btn_best.configure(state="normal")
            self.progress.pack_forget()
            self.show_best(results, svc_running)

        def restore_button():
            # если подбор упал с ошибкой, возвращаем кнопку в обычный вид
            if self.busy:
                self.after(300, restore_button)
            elif self.btn_best.text == "Остановить подбор":
                self.btn_best.configure(text="Подобрать автоматически", command=self.on_find_best, kind="secondary")
                self.progress.pack_forget()

        self.task("Подбор стратегии", job, done)
        self.btn_best.configure(state="normal")  # кнопка отмены должна нажиматься во время подбора
        self.after(300, restore_button)

    def show_best(self, results, svc_installed):
        working = [r for r in results if r["ok"]]
        if not working:
            self.lbl_best.configure(text="Ни одна стратегия не открыла сайты. Проверьте интернет и Secure DNS.")
            return
        best = working[0]
        self.lbl_best.configure(text=f"Лучшая: «{best['name']}» — открылось {best['ok']} из {best['total']} сайтов")
        self.write_log(f"Подбор завершён, лучшая стратегия: {best['name']}")
        for i, r in enumerate(working[:4]):
            row = tk.Frame(self.best_box, bg=CARD)
            row.pack(fill="x", pady=(px(10) if i == 0 else px(4), 0))
            color = GREEN if r["ok"] == r["total"] else ACCENT
            label(row, f"{i + 1}.  {r['name']}", 10, bold=(i == 0)).pack(side="left")
            if i == 0:
                apply = Button(row, "Применить", lambda n=r["name"]: self.apply_best(n, svc_installed),
                               kind="primary", width=120, height=30)
                apply.pack(side="right", padx=(px(10), 0))
            ms = f" · {r['ms']} мс" if r["ms"] else ""
            label(row, f"{r['ok']}/{r['total']}{ms}", 10, color).pack(side="right")

    def apply_best(self, name, svc_installed):
        self.dd_strategy.set(name)
        if svc_installed or self.z.service_state():
            self.install_service(name)
        else:
            self.remember_strategy(name)

            def job():
                self.z.start(name)
                self.log(f"Запущено: {name}")
            self.task("Запуск", job)
        self.show_page("home")

    # --- Telegram ---
    def build_tg(self, p):
        self.pill_tg = self.page_header(p, "Telegram", "Ускорение Telegram Desktop через TG WS Proxy")
        card = Card(p, padding=20)
        card.pack(fill="x")
        c = card.inner
        label(c, "Локальный прокси от автора zapret (Flowseal). Ускоряет загрузку сообщений, фото и видео. "
                 "Работает на вашем компьютере, без сторонних серверов. Ключ подключения хранится отдельно, "
                 "поэтому после обновлений Telegram подключать заново не нужно.",
              10, MUTED, wraplength=px(560)).pack(anchor="w")
        self.lbl_tg = label(c, "", 12, bold=True)
        self.lbl_tg.pack(anchor="w", pady=(px(16), px(10)))
        row = tk.Frame(c, bg=CARD)
        row.pack(fill="x")
        self.btn_tg_run = Button(row, "", self.on_tg_run, kind="primary", height=42)
        self.btn_tg_run.pack(side="left", fill="x", expand=True)
        self.btn_tg_link = Button(row, "Подключить Telegram", self.on_tg_link, height=42, icon=ICON_LINK)
        self.btn_tg_link.pack(side="left", fill="x", expand=True, padx=(px(10), 0))

        card = Card(p)
        card.pack(fill="x", pady=(px(14), 0))
        r = tk.Frame(card.inner, bg=CARD)
        r.pack(fill="x")
        self.var_tg_auto = tk.BooleanVar()
        self.tgl_tg_auto = Toggle(r, self.var_tg_auto, self.on_tg_autostart)
        self.tgl_tg_auto.pack(side="right")
        label(r, "Запускать вместе с Windows", 10, bold=True).pack(anchor="w")
        label(r, "Иначе Telegram с включённым прокси не подключится, пока прокси выключен", 9, MUTED).pack(anchor="w")

        card = Card(p, "Если не подключается")
        card.pack(fill="x", pady=(px(14), 0))
        label(card.inner, "Telegram → Настройки → Продвинутые → Тип подключения → MTProto-прокси 127.0.0.1:1443.\n"
                          "Если не грузятся фото, в настройках прокси (значок в трее) оставьте в DC → IP "
                          "только 4:149.154.167.220.", 9, MUTED, wraplength=px(560)).pack(anchor="w")
        self.controls += [self.btn_tg_run, self.btn_tg_link, self.tgl_tg_auto]

    # --- Настройки ---
    def build_settings(self, p):
        self.page_header(p, "Настройки")
        card = Card(p, "Обход")
        card.pack(fill="x")

        def setting_row(parent, title, desc, widget_factory, first=False):
            r = tk.Frame(parent, bg=CARD)
            r.pack(fill="x", pady=(0 if first else px(14), 0))
            w = widget_factory(r)
            w.pack(side="right")
            label(r, title, 10, bold=True).pack(anchor="w")
            label(r, desc, 9, MUTED).pack(anchor="w")
            return w

        self.dd_game = setting_row(card.inner, "Обход для игр", "Порты выше 1023: игры, лаунчеры, голосовые чаты",
                                   lambda r: Dropdown(r, list(GAME_MODES.values()), self.on_game, width=250),
                                   first=True)
        self.dd_ipset = setting_row(card.inner, "Список IP (IPSet)", "Обход по IP-адресам, а не только по сайтам",
                                    lambda r: Dropdown(r, list(IPSET_MODES.values()), self.on_ipset, width=250))

        card = Card(p, "Программа")
        card.pack(fill="x", pady=(px(14), 0))
        self.var_updates = tk.BooleanVar()
        self.tgl_updates = setting_row(card.inner, "Проверять обновления при открытии",
                                       "zapret и TG WS Proxy с GitHub",
                                       lambda r: Toggle(r, self.var_updates, self.on_updates_toggle), first=True)
        self.btn_change_dir = setting_row(card.inner, "Папка zapret", str(self.z.root),
                                          lambda r: Button(r, "Сменить", lambda: self.show_setup(can_cancel=True),
                                                           width=110, height=34, icon=ICON_FOLDER))
        self.controls += [self.dd_game, self.dd_ipset, self.tgl_updates, self.btn_change_dir]

    # --- Инструменты ---
    def build_tools(self, p):
        self.page_header(p, "Инструменты")
        card = Card(p, "Обновления")
        card.pack(fill="x")
        r = tk.Frame(card.inner, bg=CARD)
        r.pack(fill="x")
        self.btn_update = Button(r, "Проверить", self.on_update, width=150, height=36, icon=ICON_SYNC)
        self.btn_update.pack(side="right")
        self.lbl_version = label(r, "", 10, bold=True)
        self.lbl_version.pack(anchor="w")
        self.lbl_version2 = label(r, "", 9, MUTED)
        self.lbl_version2.pack(anchor="w")

        card = Card(p, "Списки и проверка")
        card.pack(fill="x", pady=(px(14), 0))
        grid = tk.Frame(card.inner, bg=CARD)
        grid.pack(fill="x")
        for c in range(3):
            grid.columnconfigure(c, weight=1, uniform="t")
        tools = [
            ("Мои сайты", ICON_EDIT, lambda: self.open_list("list-general-user.txt")),
            ("Исключения", ICON_EDIT, lambda: self.open_list("list-exclude-user.txt")),
            ("Обновить IP", ICON_SYNC, self.on_update_ipset),
            ("Проверка подключения", ICON_CHECK, lambda: self.show_page("check")),
            ("Тест из zapret", ICON_TEST, self.on_test),
            ("Открыть папку", ICON_FOLDER, lambda: os.startfile(self.z.root)),
        ]
        for i, (text, icon, cmd) in enumerate(tools):
            b = Button(grid, text, cmd, height=38, icon=icon)
            b.grid(row=i // 3, column=i % 3, sticky="ew", padx=px(3), pady=px(3))
            self.controls.append(b)

        card = Card(p, "Журнал")
        card.pack(fill="both", expand=True, pady=(px(14), 0))
        self.txt_log = tk.Text(card.inner, height=6, bg=CARD, fg=MUTED, relief="flat", bd=0,
                               highlightthickness=0, font=("Consolas", 9), state="disabled", wrap="word")
        self.txt_log.pack(fill="both", expand=True)
        self.controls.append(self.btn_update)

    # ------------------------------------------------------------------
    # Состояние
    # ------------------------------------------------------------------
    def fill_strategies(self):
        items = self.z.strategies()
        self.dd_strategy.configure(values=items)
        want = self.z.service_strategy() or self.cfg.get("last_strategy", "general")
        self.dd_strategy.set(want if want in items else (items[0] if items else ""))

    def update_versions(self):
        tg = self.tg.version() if self.tg.installed() else "не установлен"
        self.lbl_side_ver.configure(text=f"zapret {self.z.version()}\nTG WS Proxy {tg}")
        if self.pending_release or self.pending_tg:
            parts = []
            if self.pending_release:
                parts.append(f"zapret {self.pending_release['tag_name']}")
            if self.pending_tg:
                parts.append(f"TG WS Proxy {self.pending_tg['tag_name']}")
            self.lbl_version.configure(text="Доступно обновление", fg=BLUE)
            self.lbl_version2.configure(text=", ".join(parts))
            self.btn_update.configure(text="Обновить", kind="primary")
            self.btn_side_update.pack(fill="x", pady=(px(10), 0))
        else:
            self.lbl_version.configure(text="Установлены последние версии" if self.checked else "Версии", fg=TEXT)
            self.lbl_version2.configure(text=f"zapret {self.z.version()} · TG WS Proxy {tg}")
            self.btn_update.configure(text="Проверить", kind="secondary")
            self.btn_side_update.pack_forget()

    checked = False

    def poll(self):
        if not self.busy and self.z and getattr(self, "lbl_status", None) and self.lbl_status.winfo_exists():
            self.refresh()
        self.after(3000, self.poll)

    def refresh(self):
        if not self.z or not getattr(self, "lbl_status", None) or not self.lbl_status.winfo_exists():
            return
        state = self.z.service_state()
        running = self.z.winws_running()
        svc_strategy = self.z.service_strategy() if state else None

        if state == "RUNNING":
            self.pill_home.set("Работает", GREEN, GREEN_SOFT)
            self.lbl_status.configure(text="Обход включён", fg=GREEN)
            self.lbl_detail.configure(text=f"Работает как служба Windows · стратегия «{svc_strategy}»")
        elif running:
            self.pill_home.set("Работает", GREEN, GREEN_SOFT)
            self.lbl_status.configure(text="Обход включён", fg=GREEN)
            self.lbl_detail.configure(text=f"Стратегия «{self.cfg.get('last_strategy', '?')}» · "
                                           "продолжит работать после закрытия окна")
        else:
            self.pill_home.set("Выключен", RED, RED_SOFT)
            self.lbl_status.configure(text="Обход выключен", fg=TEXT)
            self.lbl_detail.configure(text="Выберите стратегию и нажмите «Запустить». "
                                           "Если сайты не открываются — попробуйте другую.")
        self.btn_start.configure(text="Перезапустить" if running and not state else "Запустить")
        self.var_svc.set(bool(state))
        self.lbl_svc.configure(text=f"Стратегия «{svc_strategy}»" if state else "Выключен")

        if not self.tg.installed():
            self.pill_tg.set("Не установлен", MUTED, CARD_HI)
            self.lbl_tg.configure(text="Прокси не установлен", fg=TEXT)
            self.lbl_tg_home.configure(text="Не установлен", fg=MUTED)
            run_text, tg_on = "Установить", False
        elif self.tg.running():
            self.pill_tg.set("Работает", GREEN, GREEN_SOFT)
            self.lbl_tg.configure(text="Прокси работает", fg=GREEN)
            self.lbl_tg_home.configure(text="● Ускорение включено", fg=GREEN)
            run_text, tg_on = "Остановить", True
        else:
            self.pill_tg.set("Выключен", RED, RED_SOFT)
            self.lbl_tg.configure(text="Прокси выключен", fg=TEXT)
            self.lbl_tg_home.configure(text="● Ускорение выключено", fg=MUTED)
            run_text, tg_on = "Запустить", False
        self.btn_tg_run.configure(text=run_text, kind="secondary" if tg_on else "primary")
        self.btn_tg_home.configure(text={"Установить": "Установить", "Остановить": "Выключить",
                                         "Запустить": "Включить"}[run_text])
        self.var_tg_auto.set(self.tg.installed() and self.tg.autostart())

        if not self.busy:
            self.btn_stop.configure(state="normal" if running or state == "RUNNING" else "disabled")
            self.btn_tg_link.configure(state="normal" if self.tg.installed() else "disabled")
            self.tgl_tg_auto.configure(state="normal" if self.tg.installed() else "disabled")

        game = GAME_MODES[self.z.game_filter()["mode"]]
        if self.dd_game.get() != game:
            self.dd_game.set(game)
        ipset = IPSET_MODES[self.z.ipset_mode()]
        if self.dd_ipset.get() != ipset:
            self.dd_ipset.set(ipset)
        self.var_updates.set(self.z.updates_flag.exists())

    def remember_strategy(self, name):
        self.cfg["last_strategy"] = name
        save_config(self.cfg)

    def restart_if_running(self):
        if self.z.service_state() == "RUNNING":
            s = self.z.service_strategy()
            if s:
                self.z.install_service(s)
                self.log("Автозапуск перезапущен с новыми настройками")
        elif self.z.winws_running():
            self.z.start(self.cfg.get("last_strategy", "general"))
            self.log("Обход перезапущен с новыми настройками")

    # ------------------------------------------------------------------
    # Обработчики
    # ------------------------------------------------------------------
    def on_start(self):
        name = self.dd_strategy.get()
        if not name:
            return
        if self.z.service_state() == "RUNNING":
            if messagebox.askyesno(APP_NAME, "Сейчас работает автозапуск. Переключить его на выбранную стратегию?",
                                   parent=self):
                self.install_service(name)
            return
        self.remember_strategy(name)

        def job():
            self.z.start(name)
            self.log(f"Запущено: {name}")
        self.task("Запуск", job)

    def on_stop(self):
        def job():
            if self.z.service_state() == "RUNNING":
                run(["sc", "stop", SERVICE])
                self.log("Автозапуск остановлен до перезагрузки")
            self.z.stop_winws()
            self.log("Обход остановлен")
        self.task("Остановка", job)

    def install_service(self, name):
        self.remember_strategy(name)

        def job():
            self.z.install_service(name)
            self.log(f"Автозапуск включён: {name}")
        self.task("Автозапуск", job)

    def on_service_toggle(self):
        if self.var_svc.get():
            self.install_service(self.dd_strategy.get())
        else:
            def job():
                self.z.remove_service()
                self.log("Автозапуск выключен")
            self.task("Автозапуск", job)

    def on_game(self):
        mode = list(GAME_MODES)[self.dd_game.current()]

        def job():
            self.z.set_game_mode(mode)
            self.log(f"Обход для игр: {GAME_MODES[mode]}")
            self.restart_if_running()
        self.task("Игровой фильтр", job)

    def on_ipset(self):
        mode = list(IPSET_MODES)[self.dd_ipset.current()]

        def job():
            self.z.set_ipset_mode(mode)
            self.log(f"Список IP: {IPSET_MODES[mode]}")
            self.restart_if_running()
        self.task("Список IP", job)

    def on_updates_toggle(self):
        if self.var_updates.get():
            self.z.updates_flag.write_text("ENABLED\r\n", "ascii")
        else:
            self.z.updates_flag.unlink(missing_ok=True)

    def on_update_ipset(self):
        def job():
            self.log("Скачиваю свежий список IP...")
            self.z.update_ipset()
            self.log("Список IP обновлён")
            self.restart_if_running()
        self.task("Список IP", job)

    def open_list(self, name):
        self.z.ensure_user_files()
        subprocess.Popen(["notepad.exe", str(self.z.lists / name)])
        self.write_log(f"Открыт {name}. После сохранения нажмите «Перезапустить».")

    def on_test(self):
        if not messagebox.askyesno(APP_NAME, "Тест по очереди запустит все стратегии и проверит доступ к Discord "
                                             "и YouTube. Это займёт несколько минут, текущий обход на время "
                                             "будет остановлен.\n\nПродолжить?", parent=self):
            return
        script = self.z.utils / "test zapret.ps1"
        subprocess.Popen(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script)],
                         creationflags=subprocess.CREATE_NEW_CONSOLE)

    # --- Telegram ---
    def on_tg_run(self):
        if not self.tg.installed():
            def job():
                self.tg.install(latest_tg_release(), self.log)
                self.tg.start()
                self.log("TG WS Proxy установлен и запущен — нажмите «Подключить Telegram»")
            self.task("Telegram", job, lambda _: (self.update_versions(), self.show_page("tg")))
        elif self.tg.running():
            self.task("Telegram", lambda: (self.tg.stop(), self.log("Прокси Telegram остановлен")))
        else:
            self.task("Telegram", lambda: (self.tg.start(), self.log("Прокси Telegram запущен")))

    def on_tg_link(self):
        def job():
            self.tg.start()
            for _ in range(30):  # при первом запуске прокси создаёт ключ не сразу
                link = self.tg.link()
                if link:
                    return link
                time.sleep(0.5)
            raise RuntimeError("Не удалось прочитать ключ прокси. Подождите пару секунд и попробуйте снова.")

        def done(link):
            os.startfile(link)
            self.write_log("Ссылка отправлена в Telegram — нажмите «Подключить» в окне Telegram")

        self.task("Telegram", job, done)

    def on_tg_autostart(self):
        self.tg.set_autostart(self.var_tg_auto.get())
        self.write_log("Прокси Telegram: автозапуск " + ("включён" if self.var_tg_auto.get() else "выключен"))

    # --- Обновления ---
    def on_update(self):
        if self.pending_release or self.pending_tg:
            self.do_update()
        else:
            self.check_updates()

    def check_updates(self, silent=False):
        local = self.z.version()
        tg_local = self.tg.version() if self.tg.installed() else None
        self.lbl_version.configure(text="Проверяю обновления...", fg=TEXT)

        def job():
            rel = latest_release()
            tg_rel = None
            if tg_local:
                try:
                    tg_rel = latest_tg_release()
                except Exception:  # noqa: BLE001 — не мешаем обновлению zapret
                    pass
            return rel, tg_rel

        def done(result):
            rel, tg_rel = result
            self.checked = True
            self.pending_release = rel if is_newer(rel["tag_name"], local) else None
            self.pending_tg = tg_rel if tg_rel and is_newer(tg_rel["tag_name"], tg_local) else None
            self.update_versions()
            news = []
            if self.pending_release:
                news.append(f"• zapret {rel['tag_name']} (у вас {local})")
            if self.pending_tg:
                news.append(f"• TG WS Proxy {tg_rel['tag_name']} (у вас {tg_local})")
            if news:
                self.write_log("Доступны обновления: " + "; ".join(n[2:] for n in news))
                if messagebox.askyesno(APP_NAME, "Вышли новые версии:\n" + "\n".join(news) +
                                       "\n\nОбновить сейчас? Ваши списки, настройки и подключение "
                                       "Telegram сохранятся.", parent=self):
                    self.after(10, self.do_update)
            elif not silent:
                self.write_log("У вас последние версии")

        self.task("Проверка обновлений", job, done)

    def do_update(self):
        rel, tg_rel = self.pending_release, self.pending_tg
        if not rel and not tg_rel:
            return

        def job():
            if rel:
                svc_strategy, was_running = download_and_install(self.z.root, rel, self.log)
                items = self.z.strategies()
                if svc_strategy:
                    s = svc_strategy if svc_strategy in items else "general"
                    self.z.install_service(s)
                    self.log(f"Автозапуск переустановлен: {s}")
                elif was_running:
                    s = self.cfg.get("last_strategy", "general")
                    self.z.start(s if s in items else "general")
                    self.log("Обход перезапущен")
            if tg_rel:
                self.tg.install(tg_rel, self.log)

        def done(_):
            self.pending_release = self.pending_tg = None
            self.fill_strategies()
            self.update_versions()
            self.write_log("Обновление завершено")

        self.task("Обновление", job, done)
