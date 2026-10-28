#!/usr/bin/env python3
"""Add the TV home-redesign strings to every locale, idempotently.

The TV module ships 11 locale files and all of them were already at parity
(221 strings each). Adding a key to just `values/` would silently drop those
screens back to English in ten languages, so this script writes the same key
set everywhere and reports which files it touched.

Re-running is safe: a key that already exists in a file is left alone, so a
partial run or a hand edit is never overwritten.
"""

import glob
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(HERE, "..", "tv", "src", "main", "res"))

# key -> {locale: value}. "values" is the default (English) bucket.
K = {
    "tv_retry": {
        "values": "RETRY", "values-de": "ERNEUT", "values-es": "REINTENTAR",
        "values-fr": "RÉESSAYER", "values-ja": "再試行", "values-ko": "재시도",
        "values-ru": "ПОВТОРИТЬ", "values-zh-rCN": "重试", "values-zh-rTW": "重試",
        "values-zh-rHK": "重試", "values-zh": "重试",
    },
    "tv_cancel": {
        "values": "CANCEL", "values-de": "ABBRECHEN", "values-es": "CANCELAR",
        "values-fr": "ANNULER", "values-ja": "キャンセル", "values-ko": "취소",
        "values-ru": "ОТМЕНА", "values-zh-rCN": "取消", "values-zh-rTW": "取消",
        "values-zh-rHK": "取消", "values-zh": "取消",
    },
    # Arrows are locale-independent; value_for() serves them from ARROW.
    "tv_arrow_up": None,
    "tv_arrow_down": None,
    "tv_card_throughput": {
        "values": "Live throughput", "values-de": "Live-Durchsatz",
        "values-es": "Rendimiento en vivo", "values-fr": "Débit en direct",
        "values-ja": "リアルタイム速度", "values-ko": "실시간 속도",
        "values-ru": "Текущая скорость", "values-zh-rCN": "实时速率",
        "values-zh-rTW": "即時速率", "values-zh-rHK": "即時速率", "values-zh": "实时速率",
    },
    "tv_card_session": {
        "values": "Session", "values-de": "Sitzung", "values-es": "Sesión",
        "values-fr": "Session", "values-ja": "セッション", "values-ko": "세션",
        "values-ru": "Сеанс", "values-zh-rCN": "会话", "values-zh-rTW": "工作階段",
        "values-zh-rHK": "工作階段", "values-zh": "会话",
    },
    "tv_session_up": {
        "values": "Uploaded", "values-de": "Hochgeladen", "values-es": "Subido",
        "values-fr": "Envoyé", "values-ja": "送信", "values-ko": "업로드",
        "values-ru": "Отправлено", "values-zh-rCN": "已上传", "values-zh-rTW": "已上傳",
        "values-zh-rHK": "已上傳", "values-zh": "已上传",
    },
    "tv_session_down": {
        "values": "Downloaded", "values-de": "Heruntergeladen", "values-es": "Bajado",
        "values-fr": "Reçu", "values-ja": "受信", "values-ko": "다운로드",
        "values-ru": "Получено", "values-zh-rCN": "已下载", "values-zh-rTW": "已下載",
        "values-zh-rHK": "已下載", "values-zh": "已下载",
    },
    "tv_session_peers": {
        "values": "Peers", "values-de": "Peers", "values-es": "Pares",
        "values-fr": "Pairs", "values-ja": "ピア", "values-ko": "피어",
        "values-ru": "Пиры", "values-zh-rCN": "节点", "values-zh-rTW": "節點",
        "values-zh-rHK": "節點", "values-zh": "节点",
    },
}

LOCALES = [
    "values", "values-de", "values-es", "values-fr", "values-ja",
    "values-ko", "values-ru", "values-zh-rCN", "values-zh-rTW",
    "values-zh-rHK", "values-zh",
]

ARROW = {"tv_arrow_up": "↑", "tv_arrow_down": "↓"}


def value_for(key: str, locale: str) -> str:
    if key in ARROW:
        return ARROW[key]
    table = K[key]
    return table.get(locale, table["values"])


def main() -> int:
    files = 0
    added = 0
    for locale in LOCALES:
        path = os.path.join(RES, locale, "strings.xml")
        if not os.path.exists(path):
            print(f"MISSING {path}", file=sys.stderr)
            return 1
        with open(path, encoding="utf-8") as fh:
            src = fh.read()

        block = []
        for key in K:
            if f'name="{key}"' in src:
                continue
            val = value_for(key, locale)
            block.append(f'    <string name="{key}">{val}</string>')

        if not block:
            continue

        if "</resources>" not in src:
            print(f"NO </resources> in {path}", file=sys.stderr)
            return 1

        src = src.replace(
            "</resources>",
            "\n".join(block) + "\n</resources>",
        )
        with open(path, "w", encoding="utf-8", newline="") as fh:
            fh.write(src)
        files += 1
        added += len(block)
        print(f"  {locale:<16} +{len(block)}")

    print(f"\n{added} string(s) added across {files} file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
