# NSFW-Schwelle: Messung

Stand 2026-10-02. Modell `Marqo/nsfw-image-detection-384` (ONNX-Export von ICIJ, Revision und
Prüfsumme in `fetch_model.py` / `server.py`), Vorverarbeitung wie in `server.py` (auf 384×384
skaliert, ohne Zuschnitt).

## Regel

Spec Abschnitt 9: die **niedrigste Schwelle, bei der höchstens rund 2 % der harmlosen Bilder
abgelehnt werden**. Im Zweifel streng – aber nicht so streng, dass der Server für gewöhnliche Bilder
unbrauchbar wird.

## Bildsatz

711 harmlose Bilder, mit `fetch_calibration.py` geholt bzw. erzeugt (nicht im Repo, die Lizenzen
sind je Datei verschieden):

| Gruppe | Anzahl | Inhalt |
|---|---|---|
| `photos` | 248 | gewöhnliche Fotos (picsum.photos) |
| `borderline` | 208 | harmlose Fotos mit viel Haut oder Körper: Beachvolleyball, Bodybuilder, Sumo, Ballett, Babys, Porträts, Hände, antike Skulpturen (Wikimedia Commons) |
| `graphics` | 255 | 146 von Wikimedia Commons (Screenshots, Balkendiagramme, Logos, Comics, Karten) und 109 hier erzeugte (einfarbige Flächen, Verläufe, Text auf dunklem und hellem Grund) |

Bilder, die tatsächlich NSFW sind, wurden **nicht** gesammelt. Wie viele solcher Bilder eine
Schwelle durchlässt, steht deshalb nicht in dieser Messung, sondern stammt aus einer fremden
(Abschnitt „Was durchrutscht“).

## Ergebnis

Verteilung der Werte:

| Gruppe | n | p50 | p90 | p95 | p99 | max |
|---|---|---|---|---|---|---|
| photos | 248 | 0,051 | 0,064 | 0,071 | 0,130 | 0,427 |
| borderline | 208 | 0,063 | 0,182 | 0,404 | 0,788 | 0,938 |
| graphics | 255 | 0,119 | 0,415 | 0,540 | 0,830 | 0,926 |
| alle | 711 | 0,064 | 0,269 | 0,415 | 0,749 | 0,938 |

Abgelehnte harmlose Bilder je Schwelle (Anzahl und Anteil):

| Schwelle | photos | borderline | graphics | alle |
|---|---|---|---|---|
| 0,30 | 2 (0,8 %) | 12 (5,8 %) | 45 (17,6 %) | 59 (8,3 %) |
| 0,40 | 1 (0,4 %) | 11 (5,3 %) | 30 (11,8 %) | 42 (5,9 %) |
| 0,50 | 0 | 7 (3,4 %) | 19 (7,5 %) | 26 (3,7 %) |
| 0,55 | 0 | 7 (3,4 %) | 11 (4,3 %) | 18 (2,5 %) |
| **0,60** | **0** | **6 (2,9 %)** | **6 (2,4 %)** | **12 (1,7 %)** |
| 0,70 | 0 | 4 (1,9 %) | 5 (2,0 %) | 9 (1,3 %) |

**Schwelle nach der Regel: 0,60** (`MEDIA_NSFW_THRESHOLD`). 0,55 läge mit 2,5 % knapp darüber.

Was bei 0,60 fälschlich abgelehnt wird (12 Bilder): drei Fotos von Sumo-Ringern, je eines von einem
Bodybuilder, vom Beachvolleyball und eine Nahaufnahme von Händen; vier Comics und zwei
Balkendiagramme. Fotos gewöhnlicher Motive: keines.

Wo das Modell schwach ist:

- **Flächen und Grafiken.** Das Modell ist an Fotos und Zeichnungen gelernt. Einfarbige Flächen
  und Verläufe bekommen Werte um 0,2 bis 0,58 (Rosa und Hauttöne am höchsten, Schwarz 0,36); bei
  0,40 würden 19 von 74 solchen Flächen abgelehnt, bei 0,60 keine. Einzelne Diagramme und Comics
  bekommen Werte über 0,8, die keine Schwelle rettet.
- **Viel Haut ohne Nacktheit** (Sumo, Bodybuilding): Werte bis 0,94.
- Screenshots mit Text, Logos, Porträts, Skulpturen, Ballett: unauffällig (höchstens 0,44).

## Was durchrutscht

Nicht hier gemessen. Die Messung von ICIJ/nuditag (670 Dateien, davon 188 explizite; gleiche
Vorverarbeitung; `BENCHMARK.md` in `github.com/ICIJ/nuditag`) nennt für dieses Modell:

| Schwelle | explizite Bilder übersehen |
|---|---|
| 0,30 | 3,2 % |
| 0,40 | 4,3 % |
| 0,50 | 5,3 % |
| 0,70 | 9,6 % |

Bei 0,60 also rund 7 %. Das ist der Preis dafür, dass Diagramme und einfarbige Banner durchgehen.
Was durchrutscht, findet der Betreiber über Meldungen und über `admin near` (angenommene Bilder
knapp unter der Schwelle) und entfernt es mit `admin ban-hash`.

## Geprüft und verworfen

- **Bild mit weißem Rand auf ein Quadrat bringen statt stauchen** (Seitenverhältnis bleibt):
  halbiert die Fehlalarme (bei 0,50: 8 statt 26 von 711). Nicht übernommen, weil für diese
  Vorverarbeitung keine Messung an expliziten Bildern vorliegt – ob dann mehr durchrutscht, ist
  unbekannt. Kandidat für später, sobald es einen Satz expliziter Testbilder gibt.
- **Mittiger Zuschnitt** (wie in der Modellkarte): kaum Unterschied bei den Fehlalarmen (25 statt
  26 bei 0,50) und übersieht, was am Bildrand liegt.

## Neu messen

Siehe `media/README.md`, Abschnitt „NSFW-Prüfung“. Wikimedia-Kategorien ändern sich; einzelne
Zahlen weichen bei einer Wiederholung ab. Maßgeblich ist die Spalte „alle“.
