# Nightscout access token for LightScout

*[Polska wersja poniżej](#token-dostępu-nightscout-dla-lightscout).*

LightScout needs its own Nightscout **access token**: one that can read your data and create, update and delete
treatments (the entries you add in the app). **Never type your `API_SECRET` into LightScout** or any other app on the
phone: it is the administrator password of your Nightscout.

Create one token per phone (for example one for each parent). Then a lost phone can be cut off without touching
the others.

## Create the token (Nightscout ver. 15.0.9)

You do this once, in a browser, on any computer or phone.

1. Open your Nightscout site **Admin Tools** by adding `/admin` to your address, for example
   `https://your-site.example/admin`.
2. If the page asks you to log in, enter your `API_SECRET` and choose **Authenticate**. Secret stays only in the
   browser; LightScout never sees it.
3. In **Roles**, choose **Add new Role** and enter exactly:
   - Name:
     ```
     lightscout
     ```
   - Permissions:
     ```
     api:treatments:create,api:treatments:update,api:treatments:delete
     ```
   Save.
4. In **Subjects**, choose **Add new Subject** and enter:
   - Name: a name for this phone, for example `lightscout-anna`.
   - Roles:
     ```
     readable,lightscout
     ```
   Save.
5. The new subject appears in the list with its **Access Token**, for example `lightscout-anna-0123456789abcdef`.
   Copy that value.
6. In LightScout, on the connection screen, enter your Nightscout address, for example `https://your-site.example/`
   and paste the access token. The app checks the version and the permissions and tells you if something is missing.

Notes:

- `readable` is a built-in Nightscout role that allows reading everything. New `lightscout` role adds writing and
  modifying treatments. LightScout refuses a token that cannot write treatments, because the entries screen would
  not work.
- The labels above follow the Nightscout ver. 15.0.9 admin page and may differ slightly in newer versions.

## If the token leaks

For example: the phone was lost or stolen, or the token was posted somewhere by mistake.

1. Open **Admin Tools** → **Subjects** and **delete** the subject of that token. The token stops working immediately,
   also in sessions that were already open: Nightscout ver. 15.0.9 checks the subject on every request. Only a
   Nightscout page that is already open in a browser may keep showing new data until it is reloaded.
2. Create a new subject and token for the phone (steps 4–6 above) and enter it in LightScout on the connection
   screen in Settings.
3. Look through the treatments of the last days in Nightscout and delete entries you do not recognise.

## If the `API_SECRET` leaks

The `API_SECRET` gives full control of your Nightscout.

1. Change it at once in your hosting (the `API_SECRET` setting or environment variable) and restart Nightscout.
2. All access tokens are derived from the `API_SECRET`, so they all change when Nightscout restarts with the new
   secret, and the old ones stop working. Copy the new tokens from **Admin Tools** → **Subjects** and enter them again
   in LightScout and in every other app that uses one (uploaders, watches).
3. Enter the new `API_SECRET` in every app that uses the secret itself instead of a token (for example an uploader
   set up with the secret).
4. Check the treatments and the profile for changes you did not make.

---

# Token dostępu Nightscout dla LightScout

LightScout potrzebuje własnego **tokenu dostępu** do Nightscouta: takiego, który odczytuje dane oraz tworzy, zmienia
i usuwa zabiegi (wpisy dodawane w aplikacji). **Nigdy nie wpisuj w LightScout ani w żadną inną aplikację w telefonie
swojego `API_SECRET`**: to hasło administratora Twojego Nightscouta.

Utwórz osobny token dla każdego telefonu (np. jeden dla każdego rodzica). Wtedy dostęp do Nightscouta ze zgubionego
telefonu wyłączysz bez konieczności odcinania pozostałych telefonów.

## Utworzenie tokenu (Nightscout wer. 15.0.9)

Token tworzysz jeden raz, w przeglądarce, na dowolnym komputerze lub telefonie.

1. Otwórz **Narzędzia administratora** wchodząc pod adres swojej strony Nightscout z dopiskiem `/admin` na końcu,
   np. `https://twoja-strona.example/admin`.
2. Jeśli strona poprosi o zalogowanie, wpisz swój `API_SECRET` i wybierz **Authenticate**. Sekret zostaje tylko
   w przeglądarce; LightScout go nie widzi.
3. W sekcji **Role** wybierz **Dodaj nową rolę** i wpisz dokładnie:
   - Nazwa:
     ```
     lightscout
     ```
   - Uprawnienia:
     ```
     api:treatments:create,api:treatments:update,api:treatments:delete
     ```
   Zapisz.
4. W sekcji **Obiekty** wybierz **Dodaj obiekt** i wpisz:
   - Nazwa: nazwę tego telefonu, np. `lightscout-anna`.
   - Role:
     ```
     readable,lightscout
     ```
   Zapisz.
5. Nowy obiekt pojawi się na liście razem z **Token dostępu**, np. `lightscout-anna-0123456789abcdef`. Skopiuj tę
   wartość.
6. W LightScout, na ekranie połączenia, wpisz adres Nightscouta, np. `https://twoja-strona.example/`, i wklej token
   dostępu. Aplikacja sprawdzi wersję i uprawnienia i powie, jeśli czegoś brakuje.

Uwagi:

- `readable` to wbudowana rola Nightscouta, która pozwala czytać wszystko. Nowa rola `lightscout` umożliwia zapis i
  modyfikację zabiegów. LightScout odrzuca token bez prawa zapisu zabiegów, bo ekran wpisów nie mógłby działać.
- Nazwy pól odpowiadają stronie administracyjnej Nightscouta w wersji 15.0.9, w nowszych wersjach mogą się nieco
  różnić.

## Gdy token wycieknie

Na przykład: telefon zgubił się, został skradziony albo token został przez pomyłkę gdzieś wklejony.

1. Otwórz **Narzędzia administratora** → **Obiekty** i **usuń** obiekt tego tokenu. Token przestanie działać od
   razu, także w sesjach już otwartych: Nightscout w wersji 15.0.9 sprawdza obiekt przy każdym zapytaniu. Tylko
   strona Nightscouta już otwarta w przeglądarce może pokazywać nowe dane do czasu jej odświeżenia.
2. Utwórz nowy obiekt i token dla telefonu (kroki 4–6) i wpisz go w LightScout na ekranie połączenia w Ustawieniach.
3. Przejrzyj w Nightscoucie zabiegi z ostatnich dni i usuń wpisy, których nie rozpoznajesz.

## Gdy wycieknie `API_SECRET`

`API_SECRET` daje pełną kontrolę nad Twoim Nightscoutem.

1. Zmień go od razu w hostingu (ustawienie lub zmienna środowiskowa `API_SECRET`) i zrestartuj Nightscouta.
2. Wszystkie tokeny dostępu są wyliczane z `API_SECRET`, więc wszystkie się zmienią po restarcie Nightscouta z nowym
   sekretem, a stare przestaną działać. Skopiuj nowe tokeny z **Narzędzia administratora** → **Obiekty** i wpisz je
   ponownie w LightScout oraz w każdej innej aplikacji, która ich używa (uploadery, zegarki).
3. Wpisz nowy `API_SECRET` w każdej aplikacji, która używa samego sekretu zamiast tokenu (np. w uploaderze
   skonfigurowanym z sekretem).
4. Sprawdź zabiegi i profil pod kątem zmian, których nie robiłeś.
