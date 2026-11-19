#!/usr/bin/env python3
"""
Syncfile 接收服务器 —— 零依赖，仅用 Python 标准库。

用法：
    python3 server.py                     # 接收文件保存到 ./Syncfile 目录
    python3 server.py --port 9000 --root /data/sync --token my-secret

手机端（同一局域网）填写：  http://<这台电脑的局域网IP>:<端口>

协议：
    POST /check  {"path","size","mtime"}  -> {"exists":bool,"size":int,"mtime":int}
    POST /upload?path=..&mtime=..         body=原始文件字节 -> {"ok":true}
    令牌：若启动时指定 --token，则要求请求头 X-Token 匹配。
"""

import argparse
import json
import os
import socket
import sys
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, unquote

ROOT = None
TOKEN = None


def safe_join(name: str) -> str:
    """把相对路径解析到 ROOT 下，拒绝路径穿越和绝对路径。"""
    rel = unquote(name).lstrip("/").replace("\\", "/")
    if not rel or rel in (".", "..") or ".." in rel.split("/"):
        raise ValueError(f"非法路径: {name!r}")
    dest = os.path.join(ROOT, rel)
    root_abs = os.path.abspath(ROOT)
    if os.path.abspath(dest) != root_abs and not os.path.abspath(dest).startswith(root_abs + os.sep):
        raise ValueError(f"非法路径: {name!r}")
    return dest


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "SyncfileServer/1.0"

    # ---------- helpers ----------

    def _authorized(self) -> bool:
        return TOKEN is None or self.headers.get("X-Token") == TOKEN

    def _deny(self, code: int, message: str):
        self._send_json(code, {"ok": False, "error": message})

    def _send_json(self, code: int, obj: dict):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length > 0 else b""

    def log_message(self, fmt, *args):  # 简洁日志
        sys.stdout.write("[%s] %s\n" % (self.log_date_time_string(), fmt % args))

    # ---------- endpoints ----------

    def do_POST(self):
        if not self._authorized():
            return self._deny(401, "令牌错误")
        url = urlparse(self.path)
        try:
            if url.path == "/check":
                return self._check()
            if url.path == "/upload":
                return self._upload(parse_qs(url.query))
            self._deny(404, f"未知接口 {url.path}")
        except (ValueError, KeyError) as e:
            self._deny(400, str(e))
        except BrokenPipeError:
            pass

    def _check(self):
        data = json.loads(self._read_body() or b"{}")
        dest = safe_join(data["path"])
        exists = os.path.isfile(dest)
        stat = os.stat(dest) if exists else None
        self._send_json(200, {
            "exists": exists,
            "size": stat.st_size if stat else 0,
            "mtime": int(round(stat.st_mtime * 1000)) if stat else 0,  # 毫秒，与客户端一致
        })

    def _upload(self, params: dict):
        rel_path = params["path"][0]
        mtime = int(params.get("mtime", ["0"])[0])
        dest = safe_join(rel_path)
        os.makedirs(os.path.dirname(dest), exist_ok=True)

        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return self._deny(400, "缺少请求体")

        # 先写临时文件，成功后原子重命名，避免留下半截文件
        fd, tmp = tempfile.mkstemp(dir=os.path.dirname(dest), prefix=".upload-")
        received = 0
        try:
            with os.fdopen(fd, "wb") as f:
                remaining = length
                while remaining > 0:
                    chunk = self.rfile.read(min(65536, remaining))
                    if not chunk:
                        raise IOError("客户端中断连接")
                    f.write(chunk)
                    remaining -= len(chunk)
                    received += len(chunk)
            if mtime > 0:
                os.utime(tmp, (mtime / 1000, mtime / 1000))  # 毫秒 -> 秒
            os.replace(tmp, dest)
        except BaseException:
            if os.path.exists(tmp):
                os.remove(tmp)
            raise
        print(f"    收到 {rel_path} ({received} 字节)")
        self._send_json(200, {"ok": True})


def lan_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127."):
                ips.add(ip)
    except OSError:
        pass
    return sorted(ips) or ["127.0.0.1"]


def main():
    global ROOT, TOKEN
    parser = argparse.ArgumentParser(description="Syncfile 接收服务器")
    parser.add_argument("--port", type=int, default=8000, help="监听端口，默认 8000")
    parser.add_argument("--root", default="Syncfile", help="文件保存目录，默认 ./Syncfile")
    parser.add_argument("--token", default="", help="访问令牌（留空则不校验）")
    args = parser.parse_args()

    ROOT = os.path.abspath(args.root)
    TOKEN = args.token or None
    os.makedirs(ROOT, exist_ok=True)

    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    print(f"Syncfile 服务器已启动，保存目录: {ROOT}")
    if TOKEN:
        print(f"访问令牌: {TOKEN}")
    print("手机端 App 里填写以下任意一个地址：")
    for ip in lan_ips():
        print(f"    http://{ip}:{args.port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已退出")


if __name__ == "__main__":
    main()
