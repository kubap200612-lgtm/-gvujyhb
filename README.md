# Rynek Analiza - dodatek do BK-Rynek 3.0 (Minecraft 1.21.4, Fabric)

Osobny mod, który analizuje rynek i wpisuje **ceny sprzedaży** prosto w listę przedmiotów BK-Rynek:
`sprzedaż = średnia 3 najtańszych ofert za sztukę x ilość sprzedaży z BK`. **Ceny skupu ustalasz sam** - mod ich nie liczy
i nie zmienia (chyba że sam wpiszesz skup w polu w panelu).
Ostatnie zmiany były kompilowane i testowane tylko na atrapach API Minecrafta (prawdziwy build zrobi dopiero GitHub Actions),
a mod nie był uruchomiony w grze.

## Jak zbudować
1. Repo z plikami budowania (`build.gradle`, `gradle.properties`, `settings.gradle`, `.github/workflows/build.yml`)
   i folderem `src/` z tej paczki. Build leci w zakładce Actions, jar jest w artefakcie "rynek-analiza".
2. Jar wrzuć do `mods/` razem z BK-Rynek (stary jar tego moda usuń).

## Panel (GUI)
- Klawisz **prawy Shift** (domyślnie) otwiera/zamyka panel. Klawisz **F8** (domyślnie) = start/stop analizy.
  Oba klawisze zmienisz w panelu, w zakładce **Ustawienia** (kliknij przycisk i naciśnij klawisz; Delete/Backspace = brak, Esc = anuluj),
  albo w Sterowaniu Minecrafta (kategoria "Rynek Analiza").
- Zakładka **Przedmioty**: wszystkie przedmioty z aktywnego profilu BK na **jednej długiej liście**, którą przewijasz kółkiem myszy,
  paskiem po prawej (uchwyt można przeciągać, klik w tor przeskakuje) albo klawiszami **PageUp / PageDown / Home / End**
  (gdy nie piszesz w polu tekstowym). Nad listą jest wyszukiwarka i przyciski **Start analizy** (z wpisaną frazą analizuje tylko pasujące) i **Stop**.
  W każdym wierszu: nazwa (z ikoną przy szerszym oknie), mały wykres **Trend** (ostatnie skany, przy szerokim oknie),
  **Śr. 3** (średnia 3 najtańszych ofert za sztukę z ostatniej analizy), **Sprzedaż** (cena sprzedaży w BK, wpisuje ją analiza;
  "551$/16" = 551$ za wystawkę 16 szt.), pole **Skup ($)** (cena skupu w BK za sztukę - patrz niżej) i przycisk **Wykres**.
- Przycisk **Wykres** rozwija wykres tego przedmiotu **pod wierszem** (lista się wydłuża, możesz mieć rozwiniętych kilka naraz).
  Zwijasz go przyciskiem **Ukryj** (w wierszu albo w nagłówku wykresu - ten drugi jest zawsze w zasięgu, nawet gdy wiersz wyjedzie z widoku).
  Po rozwinięciu lista sama przewija się tak, żeby wykres był widoczny. Osobnej zakładki z wykresami już nie ma.
- Zakładka **Ustawienia**: klawisze, szybkość analizy (Bezpieczna / Szybka / Maksymalna), **minimalna liczba ofert do ceny** (1-3, domyślnie 3),
  podatek od sprzedaży (0.97 - tylko do pokazania zysku na wykresie),
  pauza na ostatniej stronie, podgląd na rynku, wznawianie BK, ścisłe sprawdzanie komponentów,
  historia cen (zapis, obserwacja rynku podczas pracy BK, odstęp zapisów) oraz **"Wykresy: też obserwacje BK"**.
  Ustawienia są na kilku stronach (przyciski < >); przy wąskim oknie niektóre etykiety są skrócone.
- Podczas analizy panel nie otwiera się (komunikat w czacie) - zatrzymaj analizę klawiszem start/stop.
- Ostatnie pomiary i ustawienia są zapisywane w `config/rynek-analiza.json`.

## Cena sprzedaży (wpisuje analiza)
Analiza (Start / F8) dla każdego przedmiotu liczy średnią 3 najtańszych ofert **za sztukę** i wpisuje do BK `sellPrice`:
`sprzedaż = floor( średnia 3 najtańszych za sztukę x ilość sprzedaży )`.
- **Ilość sprzedaży** to pole "Ilość sprzedaży" wpisu w BK (brak = 1). BK wystawia przedmiot komendą `/wystaw {quantity} {price}`,
  gdzie `{price}` to cena **całej wystawki** - dlatego np. złote jabłka z ilością 64 dostają cenę za 64 szt. (64 x średnia za sztukę).
- Cena skupu (`maxPrice`) **nie jest zmieniana** przez analizę.
- Mod **nie włącza sprzedaży** - w BK przy przedmiocie musi być włączone "Sprzedawanie", a w ustawieniach BK "Automatyczna sprzedaż"
  (w Twoim profilu oba są teraz wyłączone). Sprawdź najpierw na jednym przedmiocie.
- Mniej ofert niż "Min. ofert do ceny" (domyślnie 3) = cena sprzedaży zostaje bez zmian (komunikat w czacie mówi, ile ofert było).
  Przy rzadkich przedmiotach możesz zejść do 2 albo 1 (wtedy jedna oferta wyznacza cenę - ostrożnie).
- Cena sprzedaży zmienia się tylko po analizie. Jeśli zmienisz w BK ilość sprzedaży, uruchom analizę tego przedmiotu ponownie
  (wpisz jego nazwę w wyszukiwarce panelu i kliknij "Start (filtr)").

## Cena skupu (ustalasz sam)
Skup ustawiasz w BK jak dotąd albo w polu **Skup ($)** w panelu (cena za sztukę, jak "Limit kupna" w BK):
- wpisana liczba trafia do BK **ok. 1 s po ostatnim wpisanym znaku** (żeby przy wpisywaniu "14000" nie poszły ceny 1, 14, 140...)
  albo od razu po zamknięciu panelu; dolna linia panelu pokazuje "skup 13490$ -> 14000$",
- puste pole albo 0 niczego nie zmienia, przecinek działa jak kropka,
- samo otwarcie i zamknięcie panelu niczego nie zapisuje.

## Wykresy (pod wierszem przedmiotu)
- Każdy przedmiot ma własny wykres ze **wszystkich analiz** (od pierwszej do ostatniej). Jeden punkt = jeden skan: wysokość = **średnia 3 najtańszych ofert za sztukę**.
  Na wykresie: oś cen z siatką (okrągłe wartości), delikatne wypełnienie pod linią, przerywana **linia średniej** ze skanów,
  pogrubiona linia z zaokrąglonymi punktami, nad punktami wartości, pod nimi **godzina skanu** (HH:mm) i data (dd.MM), gdy się zmienia.
  Najnowszy skan to większy biały punkt z niebieską "pigułką" z wartością. Gdy punktów jest dużo, a okno wąskie, podpisy są pokazywane
  co któryś punkt (zawsze ostatni), a przy bardzo wielu analizach zostaje sama linia bez kropek - szczegóły każdego punktu i tak pokaże najechanie myszą.
- Najechanie myszą na wykres pokazuje linię pionową i kartę z szczegółami najbliższego punktu: pełna data i godzina,
  źródło (skan z analizy / obserwacja BK), średnia 3, najtańsza oferta i liczba ofert.
- Pod wykresem cztery karty: **Średnia** (ze średnich 3), **Najniższa**, **Najwyższa** i **Ostatni** (z procentową zmianą względem poprzedniego skanu),
  a pod nimi linia szczegółów: `sprzedaż w BK` (z ilością, gdy > 1), `skup w BK`, `zysk/szt.` (sprzedaż za sztukę x podatek 0.97 - skup),
  `rozstęp`, `najtańsza oferta` (najtańsza pojedyncza oferta ze wszystkich analiz) i `ostatni skan` (ile minut/godzin temu).
  Karty i rozstęp też liczą się ze wszystkich analiz.
  Przy wąskim oknie linia pokazuje tyle, ile się mieści (ceny w BK i zysk są pierwsze).
- Domyślnie punkty pochodzą **tylko ze skanów z analizy**. Przełącznik **"Wykresy: też obserwacje BK"** (zakładka Ustawienia)
  dodaje zaufane pomiary z okien rynku otwieranych przez BK (patrz niżej). Wybór jest zapamiętywany.
- Przedmiot bez skanów pokazuje krótki komunikat zamiast wykresu. Wykresy zapełniają się po kolejnych analizach (Start / F8).

## Wzór
**Cena sprzedaży** (wpisywana do BK jako `sellPrice`) = `floor( średnia 3 najtańszych ofert za sztukę x ilość sprzedaży )`.
**Zysk na sztuce** (tylko pokazywany) = `sprzedaż / ilość x 0.97 - skup`.

## Co dzieje się podczas analizy
BK-Rynek jest zatrzymywany (i wznawiany po analizie, jeśli działał). Dla każdego przedmiotu:
`/ah search <nazwa>` -> PPM w repeater dwa razy ("Najniższej ceny") -> skan -> cena za sztukę z każdej pasującej oferty ->
cena sprzedaży -> `sellPrice` wpisu w BK -> na końcu zapis configu BK i przeładowanie cen. Pomiar (Śr. 3, najtańsza oferta, liczba ofert, czas)
trafia do historii (wykresy).
Mniej ofert niż minimum = cena sprzedaży nie zostaje ustawiona i pomiar nie jest zapisywany.

**Zapasowe wyszukiwanie.** Gdy po `/ah search <pełna nazwa>` na rynku nie było **żadnego** przedmiotu o tym id (albo rynek się nie otworzył),
mod próbuje krótszej frazy: bez numeru na końcu, a potem samo pierwsze słowo - np. "Anarchiczny hełm II" -> "Anarchiczny hełm" -> "Anarchiczny".
Dopasowanie BK i tak wybiera właściwy przedmiot (hełm II, nie hełm I). Gdy przedmioty tego id były, ale nie pasowały, mod nie szuka dalej,
tylko pokazuje diagnostykę (niżej). Własna fraza w `search` w configu wyłącza zapasowe wyszukiwanie dla tego przedmiotu.

**Przedmioty o tej samej nazwie** (np. 5 x "Zaklęta książka", 2 x "Diamentowy miecz") są rozróżniane po pierwszej linii lore
(potem CMD / enchanty) - w panelu widać np. "Zaklęta książka [Regeneracji I]", a każdy ma osobny wykres i pomiar.
Wcześniej ich pomiary się mieszały; ta wspólna stara historia nie jest już pokazywana, nowe wykresy zapełnią się od następnej analizy.
Przedmioty z unikalną nazwą zachowują swoją historię.

Dopasowanie ofert jak w BK: najpierw zgodne id przedmiotu (BK wybiera wpisy po id, np. `enchanted_golden_apple`),
potem `AutoMarketScanner.matchesEntry` (nazwa, enchanty, lore, custom model data...).
Zwykłe złote jabłko o nazwie "Zaklęte złote jabłko" nie przejdzie.

Zasięg skanu zależy od przedmiotu (na stałe, bez ustawienia): przedmioty niestackowalne (po 1 szt., np. hełmy) - skan kończy się,
gdy znaleziono 3 oferty, bo rynek jest posortowany od najniższej ceny; przedmioty stackowalne (np. złote jabłka) -
przeglądane są wszystkie strony, bo większy stack na dalszej stronie może mieć niższą cenę za sztukę.

## Podgląd na rynku
- Nad rynkiem pasek z ikoną i nazwą analizowanego przedmiotu, numerem w kolejce, stroną, liczbą ofert i trybem skanu.
- 3 najtańsze oferty (idą do ceny sprzedaży) mają **żółtą** ramkę. Ostatnia strona skanu z podświetleniem jest chwilę trzymana
  (`highlightPauseTicks`, domyślnie 10 ticków = 0,5 s; 0 = bez pauzy).
- Po policzeniu w czacie: "Użyte oferty: str.1 r1 k1 = 950/szt (id "nazwa" xN); ...". To ostateczny wybór;
  w trybie pełnego skanu podświetlenie na kolejnych stronach pokazuje stan chwilowy.
- Wyłączenie podglądu: zakładka Ustawienia albo `"showOverlay": false`.

## Komendy (opcjonalnie)
`/analiza start [fragment]`, `/analiza stop`, `/analiza lista` (nazwa, skup, sprzedaż i ilość każdego przedmiotu).

## Do sprawdzenia przy pierwszym teście
1. Prawy Shift otwiera panel i widać w nim przedmioty z BK (jeśli nie - komunikat błędu w panelu).
2. Analiza jednego przedmiotu (wpisz nazwę w wyszukiwarce panelu, "Start (filtr)"): w czacie "średnia 3 najtańszych = X/szt. -> sprzedaż Y$ za N szt.",
   w kolumnie "Sprzedaż" ta sama cena, a w BK "Cena sprzedaży" tego przedmiotu też. Skup w BK się nie zmienił.
3. Złote jabłka (ilość sprzedaży 64): cena sprzedaży = 64 x średnia za sztukę. **Zanim włączysz w BK sprzedawanie, sprawdź na jednym przedmiocie,
   że wystawka na rynku ma właściwą cenę.**
4. Anarchiczny hełm II: jeśli dalej "nie znaleziono żadnej pasującej oferty", w czacie pojawią się linie "BK: ..." z powodem odrzucenia,
   a pełny raport BK będzie w `config/rynek-analiza-diagnostyka.txt` - przyślij go. Jeśli "na rynku nie było żadnego przedmiotu o id..." także po
   zapasowych frazach - na rynku po prostu nie ma ofert albo serwer szuka inną frazą (wpisz ją w `search` w configu).
5. Fraza po `/ah search` to domyślnie nazwa przedmiotu bez kolorów. Jeśli serwer szuka inaczej, wpisz własną w `search` w configu.
6. Cena z lore: używany jest `lore_regex` z profilu BK, a gdy go brak - pierwsza liczba po słowie "Koszt".
7. BK porównuje cenę za sztukę tylko przy włączonym trybie "Unit" w profilu.

## Szybkość analizy
Zamiast stałych opóźnień bot czeka na faktyczną odpowiedź serwera: po każdym kliknięciu obserwuje okno rynku i idzie dalej,
gdy dane przestały się zmieniać (kilka ticków). Dodatkowo: nie klika sortowania, jeśli "Najniższej ceny" jest już ustawione,
pauza z podświetleniem jest tylko raz na przedmiot, a przerwa między przedmiotami jest krótka.
Poziomy (zakładka Ustawienia): Bezpieczna (4 ticki między kliknięciami, 3 ticki spokoju), Szybka - domyślna (2 / 2), Maksymalna (1 / 1).
Jeśli serwer zacznie odrzucać komendy lub klikanie, wybierz niższy poziom. Rzeczywisty czas zależy od pingu i serwera.

## Dopasowanie i cena - jak w BK
- Cena oferty jest czytana funkcją BK (`AutoMarketScanner.extractPrice`, ten sam skaner co w silniku BK): pomija m.in. linie
  "koszt użycia" w lore przedmiotów customowych. Własny odczyt ("Koszt" / `lore_regex`) działa tylko jako zapas.
- Liczba komponentów NIE jest sprawdzana domyślnie (BK sam jej nie sprawdza w swojej ścieżce dopasowania, więc dodatkowe
  sprawdzenie mogło odrzucać prawidłowe oferty). Włączysz je w Ustawieniach ("Ścisłe sprawdzanie komponentów").
- Gdy dla przedmiotu nie znaleziono dość ofert, w czacie pojawia się linia "Diagnostyka": ile przedmiotów o tym id było w oknie,
  ile odrzuciło dopasowanie BK (z przykładową nazwą), ile liczba komponentów, ile nie miało odczytanej ceny - albo że przedmiotów
  tego id w ogóle nie było.
- Gdy BK odrzucił oferty, mod prosi BK o jego własny raport dopasowania (`CleanMarketEngine.debug`) dla pierwszej odrzuconej oferty:
  kilka linii z sekcji FAILURES trafia do czatu ("BK: ..."), a cały raport do `config/rynek-analiza-diagnostyka.txt` (dopisywany na końcu).

## Historia cen
Plik: `config/rynek-analiza-history.json`. Zapis w tle co kilka minut, przy końcu analizy i przy zamykaniu gry.
Skany z analizy nigdy nie są usuwane przez przerzedzanie (do ok. 1000 punktów na przedmiot przerzedzane są tylko stare obserwacje).

Źródła punktów:
- **Skan z analizy** (Start / F8): po każdej analizie przedmiotu zapisywany jest pomiar (najtańsza oferta i średnia 3 najtańszych, za sztukę,
  liczba ofert, czas). To jedyne źródło wykresu w ustawieniu domyślnym i najpewniejsze dane.
- **Obserwacja podczas pracy BK** (Ustawienia -> "Obserwuj rynek, gdy działa BK"): gdy BK otworzy okno rynku, mod tylko je czyta
  (nie klika, nie zatrzymuje BK) i zapisuje ceny przedmiotów z listy - ale tylko wtedy, gdy widać prawdziwe minimum (rynek ma jedną stronę
  albo to strona 1 posortowana rosnąco po cenie). Okna przy innym sortowaniu (BK często używa "Najnowsze") nie są już zapisywane.
  Zapis nie częściej niż raz na minutę na przedmiot. Te punkty widać na wykresie po przełączeniu "skany + obserwacja BK".
- Stare punkty-próbki z poprzedniej wersji moda są w pliku, ale nie są pokazywane ani liczone.

## Czego już nie ma
- **Marża i liczenie ceny skupu** (pole "Marża", "Domyślna marża", komendy `/analiza marza` i `/analiza marza_domyslna`) - analiza liczy
  teraz cenę sprzedaży, a skup ustalasz sam. Stare pola `margins` / `defaultMargin` w configu są pomijane i znikają przy zapisie.
- **Sugerowana marża** (z rozrzutu cen, z historii, przycisk "Użyj") - usunięta w całości razem z mnożnikiem w ustawieniach i pomarańczowymi
  ramkami 4.-5. oferty. Stare pola `suggested` / `suggestFactor` w configu są ignorowane.
- **Profil rynku i podgląd decyzji BUY / WATCH / REJECT** - usunięty razem ze starym wykresem (liczył się z zakresów czasu i zaufanych pomiarów,
  których nowy wykres już nie używa).
