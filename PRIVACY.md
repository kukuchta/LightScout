# Privacy

*[Polska wersja poniżej](#prywatność).*

LightScout has no accounts, no analytics, no advertising and no crash reporting service. The author receives no
data from the app.

## What the app connects to

- **Your Nightscout site only.** The app reads readings, treatments, device status and the profile, and writes the
  entries you create (BG check, carbs, bolus, site change, sensor change). It uses HTTPS only and trusts only the
  certificate authorities built into Android.
- **The support link** (buycoffee.to) opens in your browser only when you tap Donate. The app sends nothing to it.
- **GitHub, once a day, to look for a new version.** The app asks `api.github.com` for the newest published
  release of LightScout. The request is the same for everyone: no identifier, no token, no Nightscout address and no
  app version. Like any web server, GitHub sees the phone's IP address. The result is only compared with the
  installed version; the Download button opens the release page in your browser, and the app installs nothing by
  itself. You can switch the check off in Settings under About. Nightly builds never ask.
- Nothing else.

## What is stored on the phone

- The Nightscout address and the access token. The token is encrypted with a key kept in the Android Keystore.
- A local copy of up to 31 days of Nightscout data, settings, alarm and reminder state, and a short event log with
  codes and times (no glucose values, no address, no token).
- Nothing is included in Android cloud backup or in device-to-device transfer. Uninstalling the app deletes all of
  it. Nightscout stays the source of truth.

## Permissions

| Permission | Why |
|---|---|
| Internet, network state | Connect to your Nightscout; know when the phone is offline. |
| Notifications, full-screen intent | Show alarms, including over the lock screen. |
| Foreground service, wake lock, exact alarms | Keep checking Nightscout on time with the screen off. |
| Do not disturb access (optional) | Let alarms ring when Do not disturb is on, if you allow it. |
| Ignore battery optimisation (requested) | Stop Android from pausing the checks. |
| Start at boot | Resume monitoring after the phone restarts. |
| Vibrate | Vibrate with alarms. |

## Sharing diagnostics

The app never sends diagnostics on its own. A planned "share diagnostic report" option will create a text you can
review and share yourself.

## Contact

Questions: [Discussions](https://github.com/kukuchta/LightScout/discussions). Security problems: see [SECURITY.md](SECURITY.md).

---

# Prywatność

LightScout nie ma kont, analityki, reklam ani usługi raportowania awarii. Autor nie otrzymuje z aplikacji żadnych
danych.

## Z czym łączy się aplikacja

- **Tylko z Twoim Nightscoutem.** Aplikacja odczytuje odczyty, zabiegi, stan urządzeń i profil, a zapisuje wpisy,
  które dodasz (BG check, węglowodany, bolus, zmiana wkłucia, zmiana sensora). Używa wyłącznie HTTPS i ufa tylko
  urzędom certyfikacji wbudowanym w Androida.
- **Link wsparcia** (buycoffee.to) otwiera się w przeglądarce dopiero po dotknięciu Donate. Aplikacja nic tam nie
  wysyła.
- **GitHub, raz dziennie, w poszukiwaniu nowej wersji.** Aplikacja pyta `api.github.com` o najnowsze opublikowane
  wydanie LightScout. Zapytanie jest takie samo dla wszystkich: bez identyfikatora, tokenu, adresu Nightscouta i
  numeru wersji aplikacji. Jak każdy serwer WWW, GitHub widzi adres IP telefonu. Wynik jest tylko porównywany z
  zainstalowaną wersją; przycisk Download otwiera stronę wydania w przeglądarce, a aplikacja sama niczego nie
  instaluje. Sprawdzanie można wyłączyć w Ustawieniach, w sekcji About. Wersje Nightly nigdy nie pytają.
- Z niczym więcej.

## Co jest przechowywane w telefonie

- Adres Nightscouta i token dostępu. Token jest zaszyfrowany kluczem przechowywanym w Android Keystore.
- Lokalna kopia do 31 dni danych z Nightscouta, ustawienia, stan alarmów i przypomnień oraz krótki dziennik zdarzeń
  z kodami i godzinami (bez wartości glukozy, adresu i tokenu).
- Nic nie trafia do kopii zapasowej Androida w chmurze ani do przenoszenia danych między telefonami. Odinstalowanie
  aplikacji usuwa wszystko. Źródłem danych pozostaje Nightscout.

## Uprawnienia

| Uprawnienie | Po co |
|---|---|
| Internet, stan sieci | Połączenie z Nightscoutem; wykrycie braku sieci. |
| Powiadomienia, intencja pełnoekranowa | Wyświetlanie alarmów, także na ekranie blokady. |
| Usługa pierwszoplanowa, blokada wybudzenia, dokładne alarmy | Terminowe sprawdzanie Nightscouta przy wygaszonym ekranie. |
| Dostęp do trybu Nie przeszkadzać (opcjonalny) | Alarmy dzwonią mimo trybu Nie przeszkadzać, jeśli na to pozwolisz. |
| Wyłączenie optymalizacji baterii (prośba) | Android nie wstrzymuje sprawdzania. |
| Uruchamianie po starcie | Wznowienie monitorowania po restarcie telefonu. |
| Wibracje | Wibracja przy alarmach. |

## Udostępnianie diagnostyki

Aplikacja nigdy sama nie wysyła diagnostyki. Planowana opcja „udostępnij raport diagnostyczny" utworzy tekst, który
sam przejrzysz i udostępnisz.

## Kontakt

Pytania: [Discussions](https://github.com/kukuchta/LightScout/discussions). Problemy z bezpieczeństwem: zobacz [SECURITY.md](SECURITY.md).
