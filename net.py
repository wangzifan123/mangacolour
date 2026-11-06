#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""net.py — 自适应网络访问：优先本地代理，不可用则直连。

本机情况（实测）：GitHub 域名在 hosts 里被指向 127.0.0.1，
所以"直连"实际是连到本机已关闭的端口 → WinError 10061。
必须走代理 127.0.0.1:65535。代理未启动时只能等用户开 VPN。
"""
import socket
import urllib.request

PROXY = "http://127.0.0.1:65535"


def proxy_alive(timeout=2.0) -> bool:
    s = socket.socket()
    s.settimeout(timeout)
    try:
        s.connect(("127.0.0.1", 65535))
        return True
    except Exception:                       # noqa: BLE001
        return False
    finally:
        s.close()


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def opener(auth_token: str | None = None, follow_redirects: bool = True):
    """构造 opener。auth_token 为空则不加 Authorization 头。"""
    handlers = []

    if proxy_alive():
        handlers.append(urllib.request.ProxyHandler(
            {"http": PROXY, "https": PROXY}))

    if not follow_redirects:
        handlers.append(_NoRedirect())

    if auth_token:
        class Auth(urllib.request.BaseHandler):
            def http_request(self, req):
                req.add_header("Authorization", "Bearer " + auth_token)
                req.add_header("User-Agent", "mangacolor")
                req.add_header("Accept", "application/vnd.github+json")
                return req
            https_request = http_request
        handlers.append(Auth())
    else:
        class UA(urllib.request.BaseHandler):
            def http_request(self, req):
                req.add_header("User-Agent", "mangacolor")
                return req
            https_request = http_request
        handlers.append(UA())

    return urllib.request.build_opener(*handlers)


def require_network():
    """前置检查：代理不通且目标不可达时，给出明确提示。"""
    if not proxy_alive():
        raise SystemExit(
            "代理 127.0.0.1:65535 未开启，而 GitHub 域名被 hosts 指向本机。\n"
            "请先启动 VPN/代理后再运行。"
        )
