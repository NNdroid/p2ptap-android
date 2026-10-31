"""One-shot helper: add the 12 keys introduced with the M3 settings rows to the
six non-Chinese locales, so scripts/check-i18n.sh stays green.

Each *_desc string is inserted directly after its matching switch title so the
pair reads together; the rest go at the end of the file they belong to.
"""
import io
import re

RES = "mobile/src/main/res"

# name -> {locale: translation}
DESCS = {
    "switch_accept_subnets_desc": {
        "de": "Von Peers angekündigte Subnetze in die TAP-Schnittstelle dieses Geräts routen.",
        "es": "Enrutar las subredes anunciadas por los peers a la interfaz TAP de este dispositivo.",
        "fr": "Router les sous-réseaux annoncés par les pairs vers l\\'interface TAP de cet appareil.",
        "ja": "ピアが通知したサブネットをこの端末の TAP インターフェースにルーティングします。",
        "ko": "피어가 알린 서브넷을 이 기기의 TAP 인터페이스로 라우팅합니다.",
        "ru": "Маршрутизировать анонсированные узлами подсети в TAP-интерфейс этого устройства.",
    },
    "switch_obfuscation_desc": {
        "de": "Kurze Frames auffüllen, damit DPI den Tunnel nicht fingerprinten kann.",
        "es": "Rellenar las tramas cortas para que el DPI no pueda identificar el túnel.",
        "fr": "Remplir les trames courtes pour que le DPI ne puisse pas identifier le tunnel.",
        "ja": "短いフレームをパディングし、DPI にトンネルを識別されないようにします。",
        "ko": "짧은 프레임을 패딩하여 DPI가 터널을 식별하지 못하게 합니다.",
        "ru": "Дополнять короткие кадры, чтобы DPI не мог распознать туннель.",
    },
    "switch_discover_boot_mesh_desc": {
        "de": "Dem Bootstrap-Mesh beitreten, damit Peers außerhalb des lokalen Netzes gefunden werden.",
        "es": "Unirse a la malla de arranque para encontrar peers fuera de la red local.",
        "fr": "Rejoindre le maillage d\\'amorçage pour trouver les pairs hors du réseau local.",
        "ja": "ブートストラップメッシュに参加し、ローカルネットワーク外のピアを検出します。",
        "ko": "부트스트랩 메쉬에 참여하여 로컬 네트워크 밖의 피어를 찾습니다.",
        "ru": "Войти в bootstrap-сеть, чтобы находить узлы за пределами локальной сети.",
    },
    "switch_webui_enable_desc": {
        "de": "Die lokale Konsole auf 127.0.0.1 bereitstellen.",
        "es": "Servir el panel de control local en 127.0.0.1.",
        "fr": "Servir la console locale sur 127.0.0.1.",
        "ja": "ローカルコンソールを 127.0.0.1 で提供します。",
        "ko": "127.0.0.1에서 로컬 콘솔을 제공합니다.",
        "ru": "Отдавать локальную панель на 127.0.0.1.",
    },
    "switch_enable_mdns_desc": {
        "de": "Diesen Knoten im LAN ankündigen und nahe Peers automatisch finden.",
        "es": "Anunciar este nodo en la LAN y descubrir peers cercanos automáticamente.",
        "fr": "Annoncer ce nœud sur le LAN et découvrir automatiquement les pairs proches.",
        "ja": "このノードを LAN に通知し、近くのピアを自動検出します。",
        "ko": "이 노드를 LAN에 알리고 주변 피어를 자동으로 검색합니다.",
        "ru": "Анонсировать этот узел в LAN и автоматически находить соседние узлы.",
    },
    "switch_disable_relay_desc": {
        "de": "Nur direkte Verbindungen nutzen. Peers hinter NAT sind nicht erreichbar.",
        "es": "Usar solo conexiones directas. Los peers detrás de NAT no serán accesibles.",
        "fr": "Utiliser uniquement des connexions directes. Les pairs derrière un NAT seront injoignables.",
        "ja": "直接接続のみを使用します。NAT 背後のピアには到達できません。",
        "ko": "직접 연결만 사용합니다. NAT 뒤의 피어에는 연결할 수 없습니다.",
        "ru": "Только прямые подключения. Узлы за NAT будут недоступны.",
    },
    "switch_disable_circuit_relay_desc": {
        "de": "libp2p-Circuit-Relays nicht mehr als Ausweichpfad verwenden.",
        "es": "Dejar de usar los relés de circuito de libp2p como ruta alternativa.",
        "fr": "Ne plus utiliser les relais de circuit libp2p comme chemin de repli.",
        "ja": "libp2p サーキットリレーを代替経路として使わないようにします。",
        "ko": "libp2p 서킷 릴레이를 대체 경로로 사용하지 않습니다.",
        "ru": "Не использовать circuit relay libp2p как резервный путь.",
    },
    "switch_disable_hole_punching_desc": {
        "de": "NAT-Hole-Punching überspringen und bestehende Verbindungen nutzen.",
        "es": "Omitir la perforación de NAT y usar los enlaces ya establecidos.",
        "fr": "Ignorer le perçage NAT et se fier aux liens déjà établis.",
        "ja": "NAT ホールパンチングを行わず、確立済みの接続を使います。",
        "ko": "NAT 홀펀칭을 건너뛰고 이미 설정된 연결을 사용합니다.",
        "ru": "Пропустить пробитие NAT и использовать уже установленные соединения.",
    },
    "switch_strict_key_desc": {
        "de": "Peers ablehnen, deren Identitätsschlüssel ohne Rekey wechselt.",
        "es": "Rechazar los peers cuya clave de identidad cambie sin renovación.",
        "fr": "Refuser les pairs dont la clé d\\'identité change sans renouvellement.",
        "ja": "再鍵交換なしで識別キーが変わるピアを拒否します。",
        "ko": "키 재교환 없이 신원 키가 바뀌는 피어를 거부합니다.",
        "ru": "Отклонять узлы, у которых ключ идентичности меняется без перевыпуска.",
    },
}

TAIL = {
    "action_toggle_theme": {
        "de": "Helles oder dunkles Design umschalten",
        "es": "Cambiar entre tema claro y oscuro",
        "fr": "Basculer entre thème clair et sombre",
        "ja": "ライト／ダークテーマを切り替え",
        "ko": "라이트/다크 테마 전환",
        "ru": "Переключить светлую или тёмную тему",
    },
    "btn_share_logs": {
        "de": "Protokolle teilen",
        "es": "Compartir registros",
        "fr": "Partager les journaux",
        "ja": "ログを共有",
        "ko": "로그 공유",
        "ru": "Поделиться журналами",
    },
}

TELEMETRY = {
    "telemetry_tap_mac_mismatch": {
        "de": "TAP-MAC stimmt nicht überein — die Broadcast-Adresse passt möglicherweise nicht zum tatsächlichen TAP-Gerät",
        "es": "MAC de TAP no coincide — la dirección de difusión puede no corresponder al dispositivo TAP real",
        "fr": "MAC du TAP incorrecte — l\\'adresse de diffusion ne correspond peut-être pas au périphérique TAP réel",
        "ja": "TAP MAC が一致しません — ブロードキャストアドレスが実際の TAP デバイスと一致しない可能性があります",
        "ko": "TAP MAC 불일치 — 브로드캐스트 주소가 실제 TAP 장치와 다를 수 있습니다",
        "ru": "MAC TAP не совпадает — широковещательный адрес может не соответствовать реальному TAP-устройству",
    },
}


def read(path):
    return io.open(path, encoding="utf-8", newline="").read()


def write(path, text):
    io.open(path, "w", encoding="utf-8", newline="").write(text)


def append_before_close(text, lines):
    idx = text.rindex("</resources>")
    return text[:idx] + "".join(lines) + text[idx:]


def insert_after(text, anchor_name, line):
    pat = re.compile(r'^([ \t]*)<string name="%s">.*?</string>\r?\n' % re.escape(anchor_name), re.M)
    m = pat.search(text)
    if not m:
        raise SystemExit("anchor not found: " + anchor_name)
    indent = m.group(1)
    return text[: m.end()] + indent + line.strip() + "\n" + text[m.end():]


added = 0
for locale in ("de", "es", "fr", "ja", "ko", "ru"):
    strings = RES + "/values-" + locale + "/strings.xml"
    text = read(strings)
    for name, table in DESCS.items():
        base = name[: -len("_desc")]
        text = insert_after(text, base, '<string name="%s">%s</string>' % (name, table[locale]))
        added += 1
    for name, table in TAIL.items():
        text = append_before_close(
            text, ["    <string name=\"%s\">%s</string>\n" % (name, table[locale])]
        )
        added += 1
    write(strings, text)

    tele = RES + "/values-" + locale + "/telemetry_strings.xml"
    ttext = read(tele)
    for name, table in TELEMETRY.items():
        ttext = append_before_close(
            ttext, ["    <string name=\"%s\">%s</string>\n" % (name, table[locale])]
        )
        added += 1
    write(tele, ttext)

print("inserted", added, "strings across 6 locales")
