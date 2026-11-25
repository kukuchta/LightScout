# Nightscout access token for LightScout

*Polska wersja poniżej.*

LightScout needs its own Nightscout **access token**: one that can read your data and create, update and delete
treatments (the entries you add in the app). **Never type your `API_SECRET` into LightScout** or any other app on the
phone: it is the administrator password of your Nightscout.

Create one token per phone (for example one for each parent). Then a lost phone can be cut off without touching
the others.

## Create the token (Nightscout 15)

You do this once, in a browser, on any computer or phone.

1. Open your Nightscout site and go to **Admin tools**: add `/admin` to your address, for example
   `https://your-site.example/admin`.
2. If the page asks you to log in, choose **Authenticate** and enter your `API_SECRET`. This stays in the browser;
   LightScout never sees it.
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
6. In LightScout, on the connection screen, enter your Nightscout address and paste the access token. The app checks
   the version and the permissions and tells you if something is missing.

Notes:

- `readable` is a built-in Nightscout role that allows reading everything. `lightscout` adds writing treatments.
  LightScout refuses a token that cannot write treatments, because the entries screen would not work.
- You do not need the `admin` role. Do not give it to the phone.
- The labels above follow the Nightscout 15 admin page and may differ slightly in other versions.

## If the token leaks

For example: the phone was lost or stolen, or the token was posted somewhere by mistake.

1. Open **Admin tools** → **Subjects** and **delete** the subject of that token. New logins with it stop working at
   once. A session that was already open may stay valid until it expires (up to 8 hours on Nightscout 15).
2. Create a new subject and token for the phone (steps 4–6 above) and enter it in LightScout on the connection
   screen in Settings.
3. Look through the treatments of the last days in Nightscout and delete entries you do not recognise.

## If the `API_SECRET` leaks

The `API_SECRET` gives full control of your Nightscout.

1. Change it at once in your hosting (the `API_SECRET` setting or environment variable) and restart Nightscout.
2. All access tokens are derived from the `API_SECRET`, so they all change. Copy the new tokens from **Admin tools →
   Subjects** and enter them again in LightScout and in every other app that uses one (uploaders, watches).
3. Check the treatments and the profile for changes you did not make.

---

# Token dostępu Nightscout dla LightScout

LightScout potrzebuje własnego **tokenu dostępu** do Nightscouta: takiego, który odczytuje dane oraz tworzy, zmienia
i usuwa zabiegi (wpisy dodawane w aplikacji). **Nigdy nie wpisuj w LightScout ani w żadną inną aplikację w telefonie
swojego `API_SECRET`**: to hasło administratora Twojego Nightscouta.

Utwórz osobny token dla każdego telefonu (np. jeden dla każdego rodzica). Wtedy zgubiony telefon odetniesz bez
ruszania pozostałych.

## Utworzenie tokenu (Nightscout 15)

Robisz to raz, w przeglądarce, na dowolnym komputerze lub telefonie.

1. Otwórz stronę Nightscouta i przejdź do **Admin tools**: dopisz `/admin` do adresu, np.
   `https://twoja-strona.example/admin`.
2. Jeśli strona poprosi o zalogowanie, wybierz **Authenticate** i wpisz `API_SECRET`. Zostaje on w przeglądarce;
   LightScout go nie widzi.
3. W sekcji **Roles** wybierz **Add new Role** i wpisz dokładnie:
   - Name:
     ```
     lightscout
     ```
   - Permissions:
     ```
     api:treatments:create,api:treatments:update,api:treatments:delete
     ```
   Zapisz.
4. W sekcji **Subjects** wybierz **Add new Subject** i wpisz:
   - Name: nazwę tego telefonu, np. `lightscout-anna`.
   - Roles:
     ```
     readable,lightscout
     ```
   Zapisz.
5. Nowy podmiot pojawi się na liście razem z **Access Token**, np. `lightscout-anna-0123456789abcdef`. Skopiuj tę
   wartość.
6. W LightScout, na ekranie połączenia, wpisz adres Nightscouta i wklej token dostępu. Aplikacja sprawdzi wersję i
   uprawnienia i powie, jeśli czegoś brakuje.

Uwagi:

- `readable` to wbudowana rola Nightscouta, która pozwala czytać wszystko. `lightscout` dodaje zapis zabiegów.
  LightScout odrzuca token bez prawa zapisu zabiegów, bo ekran wpisów nie mógłby działać.
- Rola `admin` nie jest potrzebna. Nie nadawaj jej telefonowi.
- Nazwy pól odpowiadają stronie administracyjnej Nightscouta 15 i w innych wersjach mogą się nieco różnić.

## Gdy token wycieknie

Na przykład: telefon zginął lub został skradziony albo token został przez pomyłkę gdzieś wklejony.

1. Otwórz **Admin tools** → **Subjects** i **usuń** podmiot tego tokenu. Nowe logowania tym tokenem przestaną
   działać od razu. Sesja już otwarta może działać do wygaśnięcia (do 8 godzin w Nightscoucie 15).
2. Utwórz nowy podmiot i token dla telefonu (kroki 4–6) i wpisz go w LightScout na ekranie połączenia w Ustawieniach.
3. Przejrzyj w Nightscoucie zabiegi z ostatnich dni i usuń wpisy, których nie rozpoznajesz.

## Gdy wycieknie `API_SECRET`

`API_SECRET` daje pełną kontrolę nad Twoim Nightscoutem.

1. Zmień go od razu w hostingu (ustawienie lub zmienna środowiskowa `API_SECRET`) i zrestartuj Nightscouta.
2. Wszystkie tokeny dostępu są wyliczane z `API_SECRET`, więc wszystkie się zmienią. Skopiuj nowe tokeny z **Admin
   tools → Subjects** i wpisz je ponownie w LightScout oraz w każdej innej aplikacji, która ich używa (uploadery,
   zegarki).
3. Sprawdź zabiegi i profil pod kątem zmian, których nie robiłeś.
