# Raster V1 – lokaler Abnahmebericht

Stand: 16. September 2026. **Durch den Benutzer mit dokumentierten Ausnahmen abgenommen.**

## Abnahmeentscheidung

Der Benutzer hat nach Kenntnis der unten aufgeführten offenen Nachweise ausdrücklich erklärt:
„Ich gebe die Abnahme frei.“ Die Abnahme gilt für die unten über SHA-256 bezeichneten Raster-
und Vector/Raster-Artefakte. Sie umfasst die sieben statistisch offenen Leistungsszenarien
und die fehlenden installierten Windows-Nachweise für Java 21/25 als akzeptierte Ausnahmen.

Die Messwerte und automatischen Prüfergebnisse bleiben unverändert: Der technische
Freigabeprüfer liefert weiterhin `ready: false`. Die Benutzerabnahme ist eine gesonderte
Entscheidung; sie stellt diese offenen Nachweise nicht als bestanden dar. Eine Veröffentlichung
wurde damit weder beauftragt noch ausgelöst.

## Ergebnis

- 17 funktionale Referenzvergleiche bestanden.
- 10 Leistungsszenarien bestanden, 7 nach 30 Paaren offen; keine nachgewiesene Regression auf den aktuellen Artefakten.
- 16 Laufzeitnachweise bestanden; HTTP-Zonenstatistik bleibt knapp offen (obere Grenze +5,18 %).
- 11 Peak-RSS-Nachweise bestanden; 6 bleiben statistisch offen.
- Grossraster, grosse abgeleitete Raster, Mehrband-, Ressourcen- und JFR-Prüfungen bestanden.
- Beide öffentlichen COGs bestanden; Swissimage 82 884 800 Bytes, DSM 65 532 368 Bytes inklusive Referenzläufen. Kein starker ETag, keine Snapshot-Garantie.
- Installierte Tests auf macOS und in Linux-ARM64-Containern mit Java 21/25 bestanden. Windows fehlt.

## Leistungswerte

Änderung gegenüber Referenzcommit `4d2a81c7305c7618eb49359739cca8500a00e0aa`. Negative Werte bedeuten geringeren Aufwand. Angegeben sind Median und 95-%-Bootstrap-Intervall. Die Entscheidung verwendet ungerundete Werte und die versionierte 5-%-Regel.

| Szenario | Paare | Laufzeit: Median [Intervall] | Peak-RSS: Median [Intervall] | Ergebnis |
|---|---:|---:|---:|---|
| long-chain | 10 | -65.11 % [-65.30; -64.96] | +2.61 % [+1.89; +3.84] | Bestanden |
| dem-small-clip | 10 | +1.38 % [+0.34; +2.27] | -18.27 % [-19.16; -15.79] | Bestanden |
| dem-large-clip | 10 | -27.18 % [-28.06; -26.61] | -23.46 % [-24.28; -22.23] | Bestanden |
| dem-nearest | 30 | +0.79 % [+0.18; +2.03] | +3.69 % [-2.32; +10.01] | Offen |
| dem-bilinear | 30 | -0.01 % [-0.13; +0.29] | +6.47 % [+1.14; +10.85] | Offen |
| dem-chain | 10 | -20.19 % [-20.40; -19.94] | -16.03 % [-17.20; -7.25] | Bestanden |
| dem-http-chain | 10 | -17.33 % [-17.98; -16.95] | -12.67 % [-15.64; -9.34] | Bestanden |
| rgb-bilinear | 30 | +0.44 % [-0.40; +2.49] | +3.71 % [+1.62; +5.59] | Offen |
| rgb-http-bilinear | 30 | +0.51 % [-0.47; +0.97] | +4.62 % [+1.96; +6.88] | Offen |
| dem-zones | 30 | +1.85 % [+1.08; +2.52] | +4.78 % [+3.89; +5.41] | Offen |
| dem-http-zones | 30 | +3.63 % [+2.06; +5.18] | +2.33 % [+0.71; +3.77] | Offen |
| crs-change | 30 | +0.38 % [+0.33; +0.61] | +1.25 % [-0.69; +2.86] | Bestanden |
| strong-resampling | 10 | +0.94 % [-2.05; +2.29] | +2.66 % [+1.94; +3.89] | Bestanden |
| polygon-holes | 10 | -93.61 % [-94.62; -93.21] | -2.69 % [-12.68; +1.58] | Bestanden |
| fan-out | 10 | -17.05 % [-17.28; -16.73] | +1.46 % [-0.21; +2.19] | Bestanden |
| repeated-clips | 10 | +0.69 % [-2.20; +1.67] | -5.01 % [-8.47; -1.70] | Bestanden |
| changing-sources | 30 | +1.26 % [+0.79; +1.99] | +3.67 % [+1.47; +5.66] | Offen |

## Behobene Probleme

- JPEG/YCbCr-TIFF mit drei Kanälen: korrekte RGB-Erkennung anhand des tatsächlich gelieferten Decoder-Ergebnisses; unabhängiger Farbtest und echte Swissimage-Prüfung bestanden.
- Lange Operationsketten: Die erste vollständige Messreihe wies +18,7 % Peak-RSS nach. Getrennte numerische Pixelschleifen senken temporären Compiler-Speicher und halten die Rechenreihenfolge unverändert. Der neue Nachweis besteht mit +2,61 % RSS, oberer Intervallgrenze +3,84 %, bei rund 65 % kürzerer Laufzeit.

## Artefakte und Nachweise

- `raster`: SHA-256 `6d40fd8f759030e01c956ee665d9320158aa16ae60243e72277596fb27ec542a`
- `vector`: SHA-256 `c294c0e45350c18ca1f2414f2111344b966a17139fead5275d2d7090eadd74db`

Die versionierten Rohberichte liegen unter `.work/acceptance-v3/`; `release.json` verknüpft sie mit SHA-256. Der Freigabeprüfer liefert erwartungsgemäss `ready: false`. Alte ZIPs und Ergebnisse liegen unter `.work/acceptance-v2/` und gelten nicht für die aktuellen Artefakte. Der Freigabe-Collector bindet Berichte über Prüfsummen und bewertet die Rohmessungen erneut. Offene Ergebnisse werden nicht als bestanden ausgegeben.

Eine CI-Veröffentlichung benötigt die kanonischen CI-Artefakte und passende Nachweise für genau deren Prüfsummen. Lokale Ergebnisse sind nicht auf neu gebaute ZIPs übertragbar.
