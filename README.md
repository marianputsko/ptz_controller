# PTZ OBSBOT Controller

Ovládač pre PTZ kamery **OBSBOT Tail 2** z tabletu: živé náhľady, joystick alebo gamepad (Xbox a iné), zoom, presety s náhľadom záberu, expozícia, ostrenie, vyváženie bielej, histogram, zebra a tally z réžie **Blackmagic ATEM**, **vMix** a **OSEE GoStream**. Zdarma, bez cloudu, všetko v lokálnej sieti.

A controller for **OBSBOT Tail 2** PTZ cameras on a tablet, with live previews, presets with thumbnails, image controls and tally from ATEM, vMix and OSEE GoStream. Free, no cloud, local network only.

## Stiahnuť / Download

- **Android (hlavná verzia):** [PTZ-Controller.apk](../../releases/latest/download/PTZ-Controller.apk). Funguje samostatne: kamery aj tally z réžie pripojí priamo.
- **Prehliadač:** [`ptz.html`](ptz.html), jeden súbor pre Chrome.
- **PTZ Bridge pre Mac (voliteľný):** [PTZ-Bridge-mac.dmg](../../releases/latest/download/PTZ-Bridge-mac.dmg). Pridá tally do HTML verzie. Apple silicon aj Intel, macOS 12+.
- **PTZ Bridge pre Windows (voliteľný):** [PTZ-Bridge.exe](../../releases/latest/download/PTZ-Bridge.exe). Windows 10/11.
- Zdrojový kód PTZ Bridge je v [`bridge/`](bridge/) a dá sa spustiť aj priamo cez Node.js.

## Štruktúra / Layout

| Priečinok | Obsah |
|---|---|
| `ptz.html` | Ovládač (rovnaký súbor beží v prehliadači aj v Android aplikácii) |
| `android/` | Android aplikácia: WebView s ovládačom a vstavaný tally server (ATEM, vMix, OSEE) na 127.0.0.1 |
| `bridge/` | PTZ Bridge pre macOS a Windows (Node.js, bez závislostí); `bridge/packaging/` zabalí program do .app a .exe |
| `.github/workflows/android.yml` | Pri každom pushi do `main` zostaví APK, Mac .dmg a Windows .exe a zverejní ich v Releases |

## Stav tally / Tally status

| Réžia | Protokol | Overené naostro |
|---|---|---|
| Blackmagic ATEM | UDP 9910 | áno |
| OSEE GoStream | UDP 19018 (JSON) | áno |
| vMix | TCP 8099 | zatiaľ len simulácia |

## Podpisový kľúč / Signing key

APK je podpísané súkromným kľúčom putsko.com, s.r.o. (SHA-256 `5E:2E:4B:C5:BC:6C:60:23:F4:83:A9:06:9F:35:18:04:8D:08:69:22:28:B2:A3:8A:0B:69:B6:E8:BE:AB:D1:A1`). Kľúč nie je v repozitári, zostavenie ho berie z GitHub secrets `PTZ_KEYSTORE_B64` a `PTZ_KEYSTORE_PASSWORD`. Verzie do 0.9.11 boli podpísané starým verejným kľúčom – pri prechode na novšiu verziu treba aplikáciu raz odinštalovať.
