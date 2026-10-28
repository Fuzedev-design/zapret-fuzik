"""Запрет от Фузика — оболочка для zapret-discord-youtube (Flowseal).

Скачивает и обновляет zapret с GitHub, запускает стратегии, включает автозапуск
и меняет настройки — всё в одном окне, без батников.
"""

import ctypes
import hashlib
import json
import os
import platform
import queue
import re
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import winreg
import zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

APP_NAME = "Запрет от Фузика"
APP_VERSION = "1.1.1"
SELF_REPO = "Fuzedev-design/zapret-fuzik"
SELF_API_LATEST = f"https://api.github.com/repos/{SELF_REPO}/releases/latest"
SELF_ASSET = "Zapret-ot-Fuzika.exe"
REPO = "Flowseal/zapret-discord-youtube"
API_LATEST = f"https://api.github.com/repos/{REPO}/releases/latest"
IPSET_URL = f"https://raw.githubusercontent.com/{REPO}/refs/heads/main/.service/ipset-service.txt"
TG_API_LATEST = "https://api.github.com/repos/Flowseal/tg-ws-proxy/releases/latest"
SERVICE = "zapret"
SERVICE_KEY = rf"System\CurrentControlSet\Services\{SERVICE}"
DEFAULT_DIR = r"C:\zapret"
IPSET_NONE = "203.0.113.113/32"

CONFIG_DIR = Path(os.environ.get("APPDATA", Path.home())) / "ZapretFuzik"
CONFIG_FILE = CONFIG_DIR / "config.json"

USER_FILES = {
    r"lists\list-general-user.txt": "# Never leave this file empty\r\ndomain.example.abc\r\n",
    r"lists\list-exclude-user.txt": "domain.example.abc\r\n",
    r"lists\ipset-exclude-user.txt": IPSET_NONE + "\r\n",
}
PRESERVE = list(USER_FILES) + [r"utils\game_filter.enabled"]

NO_WINDOW = subprocess.CREATE_NO_WINDOW

GAME_MODES = {"disabled": "Выключен", "all": "TCP и UDP (рекомендуется)", "tcp": "Только TCP", "udp": "Только UDP"}
IPSET_MODES = {"loaded": "По списку (обычный режим)", "none": "Выключен", "any": "Для всех IP (агрессивно)"}


# --- Общие утилиты ---------------------------------------------------------

def is_admin():
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except OSError:
        return False


def relaunch_as_admin():
    if getattr(sys, "frozen", False):
        exe, params = sys.executable, subprocess.list2cmdline(sys.argv[1:])
    else:
        exe, params = sys.executable, subprocess.list2cmdline([os.path.abspath(sys.argv[0])] + sys.argv[1:])
    ctypes.windll.shell32.ShellExecuteW(None, "runas", exe, params, None, 1)


def run(args, check=False):
    r = subprocess.run(args, capture_output=True, stdin=subprocess.DEVNULL, creationflags=NO_WINDOW)
    if check and r.returncode != 0:
        raise RuntimeError(r.stdout.decode("cp866", "replace") + r.stderr.decode("cp866", "replace"))
    return r


def tls_context():
    """Проверка сертификатов средствами Windows, как в браузере: недостающие
    корневые сертификаты система догрузит сама."""
    try:
        import truststore
        return truststore.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    except Exception:
        return ssl.create_default_context()


def http_get(url, timeout=20):
    ctx = tls_context()

    def make_req():
        return urllib.request.Request(url, headers={"User-Agent": "ZapretFuzik"})
    try:
        with urllib.request.urlopen(make_req(), timeout=timeout, context=ctx) as r:
            return r.read()
    except urllib.error.HTTPError:
        raise
    except (urllib.error.URLError, OSError):
        # В Windows может остаться включённым прокси от выключенного VPN
        # (например, 127.0.0.1:10809) — тогда пробуем напрямую, без прокси.
        if not urllib.request.getproxies():
            raise
    direct = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                         urllib.request.HTTPSHandler(context=ctx))
    with direct.open(make_req(), timeout=timeout) as r:
        return r.read()


def version_key(v):
    v = v.strip().lstrip("vV")  # теги релизов бывают и "v1.1.0", и "1.1.0"
    nums = [int(x) for x in re.findall(r"\d+", v)]
    nums += [0] * (4 - len(nums))
    return nums, re.sub(r"[\d.]", "", v)


def is_newer(remote, local):
    return version_key(remote) > version_key(local)


def file_hash(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def natural_key(s):
    return re.sub(r"\d+", lambda m: m.group(0).zfill(8), s.lower())


# --- Настройки программы ---------------------------------------------------

def load_config():
    try:
        return json.loads(CONFIG_FILE.read_text("utf-8"))
    except (OSError, ValueError):
        return {}


def save_config(cfg):
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    CONFIG_FILE.write_text(json.dumps(cfg, ensure_ascii=False, indent=2), "utf-8")


# --- Работа с установкой zapret --------------------------------------------

class Zapret:
    def __init__(self, root):
        self.root = Path(root)
        self.bin = self.root / "bin"
        self.lists = self.root / "lists"
        self.utils = self.root / "utils"
        self.winws = self.bin / "winws.exe"
        self.game_file = self.utils / "game_filter.enabled"
        self.updates_flag = self.utils / "check_updates.enabled"
        self.ipset = self.lists / "ipset-all.txt"
        self.ipset_backup = self.lists / "ipset-all.txt.backup"

    @staticmethod
    def is_installed(root):
        return root and (Path(root) / "bin" / "winws.exe").exists() and (Path(root) / "service.bat").exists()

    def version(self):
        try:
            m = re.search(r'set "LOCAL_VERSION=([^"]+)"', (self.root / "service.bat").read_text("utf-8", "replace"))
            return m.group(1) if m else "?"
        except OSError:
            return "?"

    def ensure_user_files(self):
        for rel, default in USER_FILES.items():
            p = self.root / rel
            if not p.exists():
                p.write_text(default, "ascii")

    # Стратегии
    def strategies(self):
        return sorted((p.stem for p in self.root.glob("general*.bat")), key=natural_key)

    def strategy_args(self, name):
        cmd, capture = "", False
        for line in (self.root / f"{name}.bat").read_text("utf-8", "replace").splitlines():
            if not capture and "winws.exe" in line:
                capture = True
            if not capture:
                continue
            t = line.rstrip()
            if t.endswith("^"):
                cmd += t[:-1] + " "
            else:
                cmd += t
                break
        marker = 'winws.exe"'
        i = cmd.find(marker)
        if i < 0:
            raise RuntimeError(f"Не удалось прочитать параметры из {name}.bat")
        a = cmd[i + len(marker):].strip()

        gf = self.game_filter()
        tcp = gf["tcp"] if gf["mode"] in ("all", "tcp") else "12"
        udp = gf["udp"] if gf["mode"] in ("all", "udp") else "12"

        a = re.sub(r"\^(.)", r"\1", a)  # экранирование cmd: ^! -> !
        for k, v in {"%BIN%": f"{self.bin}\\", "%LISTS%": f"{self.lists}\\", "%~dp0": f"{self.root}\\",
                     "%GameFilterTCP%": tcp, "%GameFilterUDP%": udp, "%GameFilter%": tcp}.items():
            a = a.replace(k, v)
        return a

    # Процесс и служба
    @staticmethod
    def winws_running():
        out = run(["tasklist", "/FI", "IMAGENAME eq winws.exe", "/NH"]).stdout.decode("cp866", "replace")
        return "winws.exe" in out.lower()

    @staticmethod
    def service_state():
        """None — службы нет, иначе 'RUNNING' / 'STOPPED' / ..."""
        r = run(["sc", "query", SERVICE])
        if r.returncode != 0:
            return None
        m = re.search(r"STATE\s*:\s*\d+\s+(\w+)", r.stdout.decode("cp866", "replace"))
        return m.group(1) if m else "UNKNOWN"

    @staticmethod
    def service_strategy():
        try:
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, SERVICE_KEY) as k:
                return winreg.QueryValueEx(k, "zapret-discord-youtube")[0]
        except OSError:
            return None

    @staticmethod
    def stop_winws():
        run(["taskkill", "/IM", "winws.exe", "/F"])
        time.sleep(0.3)

    @staticmethod
    def enable_tcp_timestamps():
        run(["netsh", "interface", "tcp", "set", "global", "timestamps=enabled"])

    def start(self, name):
        self.stop_winws()
        self.ensure_user_files()
        self.enable_tcp_timestamps()
        args = self.strategy_args(name)
        subprocess.Popen(f'"{self.winws}" {args}', cwd=str(self.bin), creationflags=NO_WINDOW,
                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(1)
        if not self.winws_running():
            raise RuntimeError("winws.exe завершился сразу после запуска. Попробуйте другую стратегию.")

    def install_service(self, name):
        self.remove_service(keep_driver=True)
        self.ensure_user_files()
        self.enable_tcp_timestamps()
        args = self.strategy_args(name)
        binpath = f'"{self.winws}" {args}'
        run(["sc", "create", SERVICE, "binPath=", binpath, "DisplayName=", "zapret", "start=", "auto"], check=True)
        run(["sc", "description", SERVICE, "Zapret DPI bypass software"])
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, SERVICE_KEY, 0, winreg.KEY_SET_VALUE) as k:
            winreg.SetValueEx(k, "zapret-discord-youtube", 0, winreg.REG_SZ, name)
        run(["sc", "start", SERVICE], check=True)

    def remove_service(self, keep_driver=False):
        if self.service_state():
            run(["sc", "stop", SERVICE])
            run(["sc", "delete", SERVICE])
            for _ in range(25):
                if not self.service_state():
                    break
                time.sleep(0.2)
        self.stop_winws()
        if not keep_driver:
            for drv in ("WinDivert", "WinDivert14"):
                run(["sc", "stop", drv])
                run(["sc", "delete", drv])

    # Игровой фильтр
    def game_filter(self):
        r = {"mode": "disabled", "tcp": "1024-65535", "udp": "1024-65535"}
        try:
            lines = self.game_file.read_text("utf-8", "replace").splitlines()
        except OSError:
            return r
        for line in lines:
            k, _, v = line.partition("=")
            k, v = k.strip().lower(), v.strip()
            if k == "mode":
                r["mode"] = v
            elif k == "all":
                r["mode"] = "all"
            elif k in ("tcp", "udp"):
                if re.fullmatch(r"[\d,\-]+", v):
                    r[k] = v
                elif not v:
                    r["mode"] = k
        if r["mode"] not in ("all", "tcp", "udp"):
            r["mode"] = "disabled"
        return r

    def set_game_mode(self, mode):
        gf = self.game_filter()
        self.game_file.write_text(f"mode={mode}\r\ntcp={gf['tcp']}\r\nudp={gf['udp']}\r\n", "ascii")

    # IPSet
    def ipset_mode(self):
        try:
            raw = self.ipset.read_text("utf-8", "replace")
        except OSError:
            return "none"
        if not raw.strip():
            return "any"
        return "none" if IPSET_NONE in raw else "loaded"

    def set_ipset_mode(self, mode):
        cur = self.ipset_mode()
        if cur == mode:
            return
        if cur == "loaded":
            os.replace(self.ipset, self.ipset_backup)
        if mode == "none":
            self.ipset.write_text(IPSET_NONE + "\r\n", "ascii")
        elif mode == "any":
            self.ipset.write_text("", "ascii")
        elif self.ipset_backup.exists():
            os.replace(self.ipset_backup, self.ipset)
        else:
            self.update_ipset()

    def update_ipset(self):
        data = http_get(IPSET_URL)
        if len(data) < 100:
            raise RuntimeError("Скачанный список IP пустой")
        target = self.ipset if self.ipset_mode() == "loaded" or not self.ipset.exists() else self.ipset_backup
        tmp = target.with_suffix(".download")
        tmp.write_bytes(data)
        os.replace(tmp, target)


# --- Telegram: TG WS Proxy (Flowseal) -------------------------------------

class TgProxy:
    """Локальный MTProto-прокси, ускоряющий Telegram Desktop.

    Ключ и настройки прокси хранятся в %APPDATA%\\TgWsProxy, отдельно от exe,
    поэтому замена exe при обновлении не ломает подключение Telegram.
    """

    EXE = "TgWsProxy.exe"
    RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
    RUN_NAME = "TgWsProxy"  # то же имя, что использует сама программа

    def __init__(self, root):
        self.dir = Path(root) / "telegram"
        self.exe = self.dir / self.EXE
        self.ver_file = self.dir / "version.txt"

    @staticmethod
    def asset_name():
        if platform.machine().upper() == "ARM64":
            return "TgWsProxy_windows_arm64.exe"
        if sys.getwindowsversion().major < 10:
            return "TgWsProxy_windows_7_64bit.exe" if platform.machine().endswith("64") else "TgWsProxy_windows_7_32bit.exe"
        return "TgWsProxy_windows.exe"

    def installed(self):
        return self.exe.exists()

    def version(self):
        try:
            return self.ver_file.read_text("utf-8").strip() or "?"
        except OSError:
            return "?"

    def running(self):
        out = run(["tasklist", "/FI", f"IMAGENAME eq {self.EXE}", "/NH"]).stdout.decode("cp866", "replace")
        return self.EXE.lower() in out.lower()

    def start(self):
        if not self.running():
            subprocess.Popen([str(self.exe)], cwd=str(self.dir), stdin=subprocess.DEVNULL,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def stop(self):
        run(["taskkill", "/IM", self.EXE, "/F"])
        time.sleep(0.5)

    def config_file(self):
        portable = self.dir / "TgWsProxy_data" / "config.json"
        if portable.exists():
            return portable
        return Path(os.environ.get("APPDATA", Path.home())) / "TgWsProxy" / "config.json"

    def link(self):
        try:
            cfg = json.loads(self.config_file().read_text("utf-8"))
        except (OSError, ValueError):
            return None
        host = cfg.get("host", "127.0.0.1")
        if host in ("0.0.0.0", "::", ""):
            host = "127.0.0.1"
        secret = cfg.get("secret")
        return f"tg://proxy?server={host}&port={cfg.get('port', 1443)}&secret=dd{secret}" if secret else None

    def autostart(self):
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, self.RUN_KEY) as k:
                return winreg.QueryValueEx(k, self.RUN_NAME)[0].strip('"').lower() == str(self.exe).lower()
        except OSError:
            return False

    def set_autostart(self, on):
        with winreg.CreateKey(winreg.HKEY_CURRENT_USER, self.RUN_KEY) as k:
            if on:
                winreg.SetValueEx(k, self.RUN_NAME, 0, winreg.REG_SZ, f'"{self.exe}"')
            else:
                try:
                    winreg.DeleteValue(k, self.RUN_NAME)
                except FileNotFoundError:
                    pass

    def install(self, release, log):
        """Ставит или обновляет прокси; если он работал — запускает снова."""
        name = self.asset_name()
        asset = next((a for a in release.get("assets", []) if a["name"] == name), None)
        if not asset:
            raise RuntimeError(f"В релизе TG WS Proxy нет файла {name}")
        self.dir.mkdir(parents=True, exist_ok=True)
        log(f"Скачиваю {name} ({asset['size'] // (1024 * 1024)} МБ)...")
        data = http_get(asset["browser_download_url"], timeout=300)
        if len(data) != asset["size"]:
            raise RuntimeError("Размер файла не совпадает, загрузка повреждена")
        tmp = self.exe.with_suffix(".new")
        tmp.write_bytes(data)
        was_running = self.running()
        if was_running:
            self.stop()
        os.replace(tmp, self.exe)
        self.ver_file.write_text(release["tag_name"], "utf-8")
        if was_running:
            self.start()
            log("Прокси Telegram перезапущен, ключ подключения тот же")


# --- Проверка подключения --------------------------------------------------
# Те же адреса, что в utils\targets.txt оригинального теста, плюс Telegram.

CHECK_TARGETS = {
    "Discord": ["discord.com", "gateway.discord.gg", "cdn.discordapp.com", "updates.discord.com"],
    "YouTube": ["www.youtube.com", "i.ytimg.com", "redirector.googlevideo.com", "youtu.be"],
    "Telegram": ["telegram.org", "web.telegram.org"],
    "Google и Cloudflare": ["www.google.com", "www.gstatic.com", "www.cloudflare.com", "cdnjs.cloudflare.com"],
}
# По этим сервисам выбирается лучшая стратегия
STRATEGY_SERVICES = ("Discord", "YouTube", "Google и Cloudflare")

PROBE_TEXT = {
    "ok": "доступен",
    "timeout": "нет ответа",
    "reset": "соединение сброшено",
    "dns": "адрес не найден (DNS)",
    "cert": "подмена сертификата",
    "error": "ошибка",
}


def probe(host, timeout=5):
    """HTTPS-запрос к сайту: ('ok' | причина ошибки, время в мс)."""
    t0 = time.perf_counter()
    try:
        ctx = tls_context()
        with socket.create_connection((host, 443), timeout=timeout) as sock:
            with ctx.wrap_socket(sock, server_hostname=host) as tls:
                tls.sendall(f"HEAD / HTTP/1.1\r\nHost: {host}\r\nUser-Agent: Mozilla/5.0\r\n"
                            "Connection: close\r\n\r\n".encode())
                ok = tls.recv(16).startswith(b"HTTP/")
        return ("ok" if ok else "error"), int((time.perf_counter() - t0) * 1000)
    except socket.gaierror:
        return "dns", None
    except ssl.SSLCertVerificationError:
        return "cert", None
    except (socket.timeout, TimeoutError):
        return "timeout", None
    except (ConnectionResetError, ConnectionAbortedError, ssl.SSLEOFError, ssl.SSLZeroReturnError):
        return "reset", None
    except OSError:
        return "error", None


def check_services(services=None, timeout=5):
    """Проверяет сервисы параллельно: {сервис: [(сайт, статус, мс), ...]}."""
    services = services or list(CHECK_TARGETS)
    jobs = [(s, h) for s in services for h in CHECK_TARGETS[s]]
    with ThreadPoolExecutor(max_workers=16) as pool:
        results = list(pool.map(lambda j: probe(j[1], timeout), jobs))
    out = {s: [] for s in services}
    for (s, h), (status, ms) in zip(jobs, results):
        out[s].append((h, status, ms))
    return out


def summarize(results):
    """Сколько сайтов открылось и средний отклик — для сравнения стратегий."""
    rows = [r for rs in results.values() for r in rs]
    ok = [ms for _, st, ms in rows if st == "ok"]
    return len(ok), len(rows), (sum(ok) // len(ok) if ok else None)


def find_best_strategy(z, progress, cancel):
    """Запускает стратегии по очереди и проверяет доступ. Возвращает список, лучшие первыми."""
    results = []
    names = z.strategies()
    try:
        for i, name in enumerate(names):
            if cancel.is_set():
                break
            progress(i, len(names), name)
            try:
                z.start(name)
            except (RuntimeError, OSError):
                results.append({"name": name, "ok": 0, "total": 0, "ms": None, "failed": True})
                continue
            time.sleep(1.5)  # даём winws перехватить трафик
            ok, total, ms = summarize(check_services(STRATEGY_SERVICES, timeout=4))
            results.append({"name": name, "ok": ok, "total": total, "ms": ms, "failed": False})
    finally:
        z.stop_winws()
    results.sort(key=lambda r: (-r["ok"], r["ms"] if r["ms"] is not None else 10 ** 9))
    return results


# --- Установка и обновление с GitHub ---------------------------------------

def latest_release():
    return json.loads(http_get(API_LATEST, timeout=15))


def latest_tg_release():
    return json.loads(http_get(TG_API_LATEST, timeout=15))


# --- Обновление самой программы --------------------------------------------

def latest_self_release():
    return json.loads(http_get(SELF_API_LATEST, timeout=15))


def current_exe():
    """Путь к exe программы; None, если запущены из исходников."""
    return Path(sys.executable) if getattr(sys, "frozen", False) else None


def cleanup_old_exe():
    """Удаляет копию прошлой версии, оставшуюся после самообновления."""
    exe = current_exe()
    if exe:
        exe.with_name(exe.name + ".old").unlink(missing_ok=True)


def install_self_update(release, exe, log):
    """Скачивает новую версию и подменяет exe.

    Windows не даёт перезаписать запущенный exe, но разрешает его переименовать:
    текущий файл становится .old (удаляется при следующем запуске), новый встаёт
    на его место под тем же именем.
    """
    asset = next((a for a in release.get("assets", []) if a["name"] == SELF_ASSET), None)
    if not asset:
        raise RuntimeError(f"В релизе {release.get('tag_name')} нет файла {SELF_ASSET}")
    log(f"Скачиваю {APP_NAME} {release['tag_name']} ({asset['size'] // (1024 * 1024)} МБ)...")
    data = http_get(asset["browser_download_url"], timeout=300)
    if len(data) != asset["size"]:
        raise RuntimeError("Размер файла не совпадает, загрузка повреждена")
    digest = asset.get("digest") or ""
    if digest.startswith("sha256:") and hashlib.sha256(data).hexdigest() != digest.split(":", 1)[1]:
        raise RuntimeError("Контрольная сумма не совпадает, файл повреждён или подменён")

    exe = Path(exe)
    new, old = exe.with_name(exe.name + ".new"), exe.with_name(exe.name + ".old")
    new.write_bytes(data)
    old.unlink(missing_ok=True)
    os.replace(exe, old)
    try:
        os.replace(new, exe)
    except OSError:
        os.replace(old, exe)  # вернуть как было
        raise
    log(f"{APP_NAME} обновлён до {release['tag_name']}")
    return exe


def download_and_install(root, release, log):
    """Ставит или обновляет zapret в папке root. Пользовательские списки и настройки сохраняются."""
    asset = next((a for a in release.get("assets", []) if a["name"].lower().endswith(".zip")), None)
    if not asset:
        raise RuntimeError("В релизе нет zip-архива")

    root = Path(root)
    root.mkdir(parents=True, exist_ok=True)
    z = Zapret(root)
    existed = Zapret.is_installed(root)

    with tempfile.TemporaryDirectory(prefix="zapret-fuzik-") as tmp:
        tmp = Path(tmp)
        log(f"Скачиваю {asset['name']} ({asset['size'] // 1024} КБ)...")
        data = http_get(asset["browser_download_url"], timeout=120)
        if len(data) != asset["size"]:
            raise RuntimeError("Размер архива не совпадает, загрузка повреждена")
        zip_path = tmp / "release.zip"
        zip_path.write_bytes(data)

        out = tmp / "x"
        with zipfile.ZipFile(zip_path) as zf:
            for m in zf.namelist():
                if not (out / m).resolve().is_relative_to(out.resolve()):
                    raise RuntimeError(f"Подозрительный путь в архиве: {m}")
            zf.extractall(out)
        svc = next(out.rglob("service.bat"), None)
        if not svc or not (svc.parent / "bin" / "winws.exe").exists():
            raise RuntimeError("Архив имеет неожиданную структуру")
        src = svc.parent

        svc_strategy = was_running = ipset_mode = None
        saved = {}
        if existed:
            ipset_mode = z.ipset_mode()
            svc_strategy = z.service_strategy() if z.service_state() else None
            was_running = z.winws_running() and not svc_strategy
            for rel in PRESERVE:
                p = root / rel
                if p.exists():
                    saved[rel] = p.read_bytes()
            log("Останавливаю обход...")
            if svc_strategy:
                run(["sc", "stop", SERVICE])
            z.stop_winws()
            run(["sc", "stop", "WinDivert"])
            for p in root.glob("general*.bat"):
                p.unlink()

        changed = 0
        for f in src.rglob("*"):
            if not f.is_file():
                continue
            dest = root / f.relative_to(src)
            if dest.exists() and file_hash(dest) == file_hash(f):
                continue
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(f, dest)
            changed += 1
        log(f"Обновлено файлов: {changed}")

    for rel, content in saved.items():
        (root / rel).write_bytes(content)
    z.ensure_user_files()
    if ipset_mode:
        # В релизе полный список IP лежит в .backup — возвращаем режим пользователя
        z.set_ipset_mode(ipset_mode)

    return svc_strategy, was_running


# --- Запуск ----------------------------------------------------------------

def main():
    if not is_admin():
        relaunch_as_admin()
        return
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)  # чёткий текст на экранах с масштабом
    except (OSError, AttributeError):
        pass
    ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("Fuzik.Zapret")  # своя иконка на панели задач
    try:
        cleanup_old_exe()
    except OSError:
        pass  # прошлая копия ещё занята — удалим в другой раз
    from app_ui import App
    App().mainloop()


if __name__ == "__main__":
    main()
