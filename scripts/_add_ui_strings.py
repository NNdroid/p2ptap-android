#!/usr/bin/env python3
"""Insert the home-screen rewrite keys into every mobile locale.

Idempotent: a key already present in a file is skipped, so this can be run
again after adding a locale or a key without duplicating entries.

Run from the repo root as: python3 scripts/_add_ui_strings.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "mobile" / "src" / "main" / "res"

KEYS = [
    "home_section_identity",
    "label_virtual_ip",
    "metric_upload",
    "metric_download",
    "metric_peers",
    "advanced_info_title",
    "home_section_runtime",
    "status_elapsed_fmt",
    "status_reason_connected_fmt",
    "btn_cancel",
    "menu_copy_full_id",
    "menu_share_qr",
    "menu_view_peers",
    "action_logs",
]

# Translations, keyed by resource qualifier ("values" is the default/English).
NEW_STRINGS = {
    "values": {
        "home_section_identity": "Node Identity",
        "label_virtual_ip": "Virtual IP",
        "metric_upload": "Up",
        "metric_download": "Down",
        "metric_peers": "Peers",
        "advanced_info_title": "Advanced",
        "home_section_runtime": "Runtime",
        "status_elapsed_fmt": "Starting engine · %1$d s",
        "status_reason_connected_fmt": "Direct %1$d · Relay %2$d",
        "btn_cancel": "Cancel",
        "menu_copy_full_id": "Copy full ID",
        "menu_share_qr": "Share QR code",
        "menu_view_peers": "View peers",
        "action_logs": "Logs",
    },
    "values-zh": {
        "home_section_identity": "节点身份",
        "label_virtual_ip": "虚拟 IP",
        "metric_upload": "上行",
        "metric_download": "下行",
        "metric_peers": "节点",
        "advanced_info_title": "高级信息",
        "home_section_runtime": "运行统计",
        "status_elapsed_fmt": "正在启动引擎 · %1$d 秒",
        "status_reason_connected_fmt": "直连 %1$d · 中继 %2$d",
        "btn_cancel": "取消",
        "menu_copy_full_id": "复制完整 ID",
        "menu_share_qr": "分享二维码",
        "menu_view_peers": "查看节点详情",
        "action_logs": "日志",
    },
    "values-zh-rCN": {
        "home_section_identity": "节点身份",
        "label_virtual_ip": "虚拟 IP",
        "metric_upload": "上行",
        "metric_download": "下行",
        "metric_peers": "节点",
        "advanced_info_title": "高级信息",
        "home_section_runtime": "运行统计",
        "status_elapsed_fmt": "正在启动引擎 · %1$d 秒",
        "status_reason_connected_fmt": "直连 %1$d · 中继 %2$d",
        "btn_cancel": "取消",
        "menu_copy_full_id": "复制完整 ID",
        "menu_share_qr": "分享二维码",
        "menu_view_peers": "查看节点详情",
        "action_logs": "日志",
    },
    "values-zh-rHK": {
        "home_section_identity": "節點身分",
        "label_virtual_ip": "虛擬 IP",
        "metric_upload": "上行",
        "metric_download": "下行",
        "metric_peers": "節點",
        "advanced_info_title": "進階資訊",
        "home_section_runtime": "執行統計",
        "status_elapsed_fmt": "正在啟動引擎 · %1$d 秒",
        "status_reason_connected_fmt": "直連 %1$d · 中繼 %2$d",
        "btn_cancel": "取消",
        "menu_copy_full_id": "複製完整 ID",
        "menu_share_qr": "分享二維碼",
        "menu_view_peers": "檢視節點詳情",
        "action_logs": "日誌",
    },
    "values-zh-rTW": {
        "home_section_identity": "節點身分",
        "label_virtual_ip": "虛擬 IP",
        "metric_upload": "上行",
        "metric_download": "下行",
        "metric_peers": "節點",
        "advanced_info_title": "進階資訊",
        "home_section_runtime": "執行統計",
        "status_elapsed_fmt": "正在啟動引擎 · %1$d 秒",
        "status_reason_connected_fmt": "直連 %1$d · 中繼 %2$d",
        "btn_cancel": "取消",
        "menu_copy_full_id": "複製完整 ID",
        "menu_share_qr": "分享二維碼",
        "menu_view_peers": "檢視節點詳情",
        "action_logs": "日誌",
    },
    "values-de": {
        "home_section_identity": "Knotenidentität",
        "label_virtual_ip": "Virtuelle IP",
        "metric_upload": "Senden",
        "metric_download": "Empfangen",
        "metric_peers": "Peers",
        "advanced_info_title": "Erweitert",
        "home_section_runtime": "Laufzeit",
        "status_elapsed_fmt": "Engine startet · %1$d s",
        "status_reason_connected_fmt": "Direkt %1$d · Relay %2$d",
        "btn_cancel": "Abbrechen",
        "menu_copy_full_id": "Vollständige ID kopieren",
        "menu_share_qr": "QR-Code teilen",
        "menu_view_peers": "Peers ansehen",
        "action_logs": "Protokolle",
    },
    "values-es": {
        "home_section_identity": "Identidad del nodo",
        "label_virtual_ip": "IP virtual",
        "metric_upload": "Subida",
        "metric_download": "Bajada",
        "metric_peers": "Pares",
        "advanced_info_title": "Avanzado",
        "home_section_runtime": "Ejecución",
        "status_elapsed_fmt": "Iniciando motor · %1$d s",
        "status_reason_connected_fmt": "Directo %1$d · Relay %2$d",
        "btn_cancel": "Cancelar",
        "menu_copy_full_id": "Copiar ID completo",
        "menu_share_qr": "Compartir código QR",
        "menu_view_peers": "Ver pares",
        "action_logs": "Registros",
    },
    "values-fr": {
        "home_section_identity": "Identité du nœud",
        "label_virtual_ip": "IP virtuelle",
        "metric_upload": "Envoi",
        "metric_download": "Réception",
        "metric_peers": "Pairs",
        "advanced_info_title": "Avancé",
        "home_section_runtime": "Exécution",
        "status_elapsed_fmt": "Démarrage du moteur · %1$d s",
        "status_reason_connected_fmt": "Direct %1$d · Relais %2$d",
        "btn_cancel": "Annuler",
        "menu_copy_full_id": "Copier l'ID complet",
        "menu_share_qr": "Partager le code QR",
        "menu_view_peers": "Voir les pairs",
        "action_logs": "Journaux",
    },
    "values-ja": {
        "home_section_identity": "ノード識別子",
        "label_virtual_ip": "仮想 IP",
        "metric_upload": "上り",
        "metric_download": "下り",
        "metric_peers": "ピア",
        "advanced_info_title": "詳細情報",
        "home_section_runtime": "稼働状況",
        "status_elapsed_fmt": "エンジン起動中 · %1$d 秒",
        "status_reason_connected_fmt": "直接 %1$d · リレー %2$d",
        "btn_cancel": "キャンセル",
        "menu_copy_full_id": "ID をすべてコピー",
        "menu_share_qr": "QR コードを共有",
        "menu_view_peers": "ピアを表示",
        "action_logs": "ログ",
    },
    "values-ko": {
        "home_section_identity": "노드 신원",
        "label_virtual_ip": "가상 IP",
        "metric_upload": "업로드",
        "metric_download": "다운로드",
        "metric_peers": "피어",
        "advanced_info_title": "고급 정보",
        "home_section_runtime": "실행 정보",
        "status_elapsed_fmt": "엔진 시작 중 · %1$d초",
        "status_reason_connected_fmt": "직접 %1$d · 릴레이 %2$d",
        "btn_cancel": "취소",
        "menu_copy_full_id": "전체 ID 복사",
        "menu_share_qr": "QR 코드 공유",
        "menu_view_peers": "피어 보기",
        "action_logs": "로그",
    },
    "values-ru": {
        "home_section_identity": "Идентичность узла",
        "label_virtual_ip": "Виртуальный IP",
        "metric_upload": "Отдача",
        "metric_download": "Приём",
        "metric_peers": "Пиры",
        "advanced_info_title": "Дополнительно",
        "home_section_runtime": "Работа",
        "status_elapsed_fmt": "Запуск движка · %1$d с",
        "status_reason_connected_fmt": "Напрямую %1$d · Relay %2$d",
        "btn_cancel": "Отмена",
        "menu_copy_full_id": "Копировать полный ID",
        "menu_share_qr": "Поделиться QR-кодом",
        "menu_view_peers": "Показать пиры",
        "action_logs": "Журнал",
    },
}


def escape_xml(s: str) -> str:
    """Escape what Android string resources require, plus the apostrophe.

    An unescaped apostrophe is legal XML but makes aapt complain about
    unquoted strings, so every locale goes through the same escape path.
    """
    return (s.replace("&", "&amp;")
             .replace("<", "&lt;")
             .replace(">", "&gt;")
             .replace("'", "\\'"))


def insert(qualifier: str, translations: dict) -> int:
    path = RES / qualifier / "strings.xml"
    if not path.exists():
        print(f"SKIP {qualifier}: no file at {path}")
        return 0

    text = path.read_text(encoding="utf-8")
    present = set(re.findall(r'<string\s+name="([^"]+)"', text))

    lines = []
    for key in KEYS:
        val = translations.get(key)
        if val is None or key in present:
            continue
        lines.append(f'    <string name="{key}">{escape_xml(val)}</string>')

    if not lines:
        print(f"{qualifier}: no changes")
        return 0

    assert "</resources>" in text, f"{qualifier}: no </resources> tag"
    block = "\n".join(lines) + "\n"
    path.write_text(text.replace("</resources>", block + "</resources>", 1), encoding="utf-8")
    print(f"{qualifier}: added {len(lines)} keys")
    return len(lines)


def main() -> int:
    missing = [q for q in NEW_STRINGS if NEW_STRINGS[q].keys() != set(KEYS)]
    for q in missing:
        print(f"ERROR {q}: key set does not match KEYS")
    if missing:
        return 1

    total = 0
    for qualifier, translations in NEW_STRINGS.items():
        total += insert(qualifier, translations)
    print(f"\nTotal keys inserted: {total}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
