# Implementierungs- und Abnahmestand

**V1 wurde am 16. September 2026 durch den Benutzer mit dokumentierten Ausnahmen abgenommen.**
Die Entscheidung umfasst sieben statistisch offene Leistungsszenarien und die fehlenden
Windows-Nachweise für Java 21/25. Details: [acceptance-results.md](acceptance-results.md).
Der automatische Nachweisstatus bleibt unverändert; der CI-Abnahmecheck ist weiterhin gesperrt.

## Implementiert

- Getrennte Core-, ValueMeta-, GeoTools- und Assembly-Module mit dem gemeinsamen Plugin-Parent.
- Unveränderliche Rasterwerte, begrenzter versionierter Codec, Clone/Vergleich/Hashing ohne Pixel-I/O.
- Metadatenplanung für Clip/Reproject; blockweise Ausführung mit begrenzten Sitzungscaches.
- Lokale GeoTIFF- und öffentliche HTTP-Quellen; GeoTIFF/BigTIFF-Writer mit temporärer Nachbardatei.
- Raster Reader, Info, Clip, Reproject, Writer und Zonal Statistics im Companion-Repository.
- Mehrbandauswahl, RGB/Alpha/Palette, NoData und Scale/Offset; Quellen- und Operationsherkunft in Fehlern.
- Migration der Dateikonfigurationen, Beispielpipelines, Error-Hop-Migration und nachvollziehbares Layout.
- Getrennte ZIPs und Prüfung der gemeinsamen Klassenidentität in installiertem Hop.
- CI für Java 21/25 und Linux/macOS/Windows; Veröffentlichung nur mit vollständiger Abnahme beider ZIPs.

Die bestehende Änderung am Vector-Reader-Dialog wurde nicht bearbeitet.

## Abnahme-Erweiterung vom 15. September 2026

- Versionierte 5-%-Regel für Laufzeit und Peak-RSS, getrennte Konfidenzintervalle, zehn bis
  dreissig Messpaare. Änderungen an Artefakten, Fixtures oder Regeln verhindern das Fortsetzen.
- Zeitmessungen benötigen einen passenden erfolgreichen Funktionsbericht. Installierte JARs
  werden gegen die angegebenen ZIPs geprüft; Statuswerte werden aus Messwerten neu berechnet.
- Range-Server und Prüfsummen arbeiten blockweise. Öffentliche COGs laufen durch einen
  nicht cachenden Proxy mit maximal 1 GiB Nutzdaten je Quelle und Lauf.
- Die 17 Vergleichsszenarien bestehen funktional. Statistikwerte werden direkt verglichen;
  beide Ausgänge der Verzweigung werden geprüft. Bestehende Pixelvergleiche bleiben exakt;
  neue Float-Fälle verwenden höchstens die vereinbarte relative Toleranz 1e-6.
- DEM/RGB bei 16384² und 32768²: vollständige Ausgabe und Pixelvergleich sowie
  100/1000/10000 unterschiedliche Clips mit `-Xmx512m` bestanden. Die grossen Ausgaben sind BigTIFF.
- Grosse abgeleitete Raster: halbe Breite/Höhe dieser vier Quellen durch Clip → Reproject → Writer
  mit `-Xmx512m` verarbeitet. Unabhängige Nearest-Sollwerte einschliesslich Kachelgrenzen bestanden.
- RGBA, Palette und numerische Vierbanddaten bei 4096² und 16384²: Polygonlöcher,
  Bandreihenfolge, unmaskierte Pixel, Palette und Cache-Grenzen bestanden.
- JFR-Schreibmessung mit tatsächlichem TIFF-Writer kalibriert. Im geprüften Ablauf nur erlaubte
  Writer-Nachbardateien; keine Zwischenraster zwischen Operationen. Erfolg, Lesefehler und
  Abbruch während des Schreibens hinterlassen keine temporären Writer-Dateien.
- Beide öffentlichen Solothurn-COGs bestehen begrenzte Kandidaten-/Referenzprüfungen:
  Swissimage rund 82,9 MB, DSM rund 65,5 MB HTTP-Nutzdaten, jeweils inklusive Referenzlauf.
  Kein starker ETag vorhanden; keine Snapshot-Garantie. DSM-Statistiken werden numerisch
  verglichen, mit exakten Counts/Extrema/Status und der vereinbarten Float-Toleranz für Aggregate.
- Gefundene Produktionslücke behoben: dreikanalige JPEG/YCbCr-TIFFs werden entsprechend den
  tatsächlich vom Decoder gelieferten RGB-Pixeln erkannt. Unabhängiger Farbvergleich unter
  Java 21 und 25 bestanden; keine Bibliotheksversion geändert.
- 100/1000/10000 unterschiedliche Zonen gegen unabhängige Sollwerte geprüft; Reader-Wiederverwendung
  und Cache-Grenzen kontrolliert. Installierte Pipelines mit 1/2/4 Statistik-Transform-Kopien
  liefern für 1000 Zonen dieselben vollständigen Ergebnismengen.
- Installierte Hop-Tests auf macOS und in Linux-ARM64-Containern mit Java 21 und 25 auf den aktualisierten ZIPs bestanden.
  Die bestehende Änderung am Vector-Reader-Dialog bleibt unberührt.

Reproduzierbare Befehle und Berichtsschemata: [performance.md](performance.md).
Aktuelle lokale Einzelberichte liegen unter `.work/acceptance-v3/`; sie sind keine automatisch
bestandene Veröffentlichung. Der Collector verknüpft Berichte mit SHA-256 und prüft alle
noch fehlenden Nachweise.

Die erste vollständige Zehn-Paar-Matrix hat bei der langen Kette eine Peak-RSS-Regression
nachgewiesen (Median +18,7 %, 95-%-Intervall +16,6 bis +22,4 %). NMT und JIT-Protokolle
zeigten grosse temporäre Compiler-Allokationen in der Reprojektionsmethode. Deren numerische
Pixelschleifen wurden intern aufgeteilt, ohne die Rechnungen oder Operationsreihenfolge zu ändern.
Ein isolierter Vorvergleich senkte RSS deutlich; er ersetzt keine Leistungsabnahme.
810000 Ausgabewerte sowie Gitter und Metadaten blieben exakt gleich.
Die korrigierten Artefakte haben unter `.work/acceptance-v3/` die lokalen Funktions-, Belastungs-
und installierten macOS-/Linux-ARM64-Prüfungen erneut bestanden. Die neue Leistungsabnahme ist abgeschlossen: zehn Szenarien bestanden, sieben nach 30 Paaren offen; keine nachgewiesene Regression.
Die lange Kette besteht auf diesem Stand zehn Messpaare: Laufzeitverhältnis 0,3489,
Peak-RSS-Verhältnis 1,0261 mit 95-%-Intervall [1,0189; 1,0384]. Damit ist die zuvor
nachgewiesene Regression in diesem Szenario behoben; die übrigen Szenarien werden separat bewertet.
Die vorherigen ZIPs sind unter `.work/acceptance-v2/artifacts/` archiviert.

## Offene Nachweise und Veröffentlichungsbedingungen

1. Sieben Leistungsszenarien bleiben nach 30 Paaren offen: sechs wegen Peak-RSS, eines
   wegen Laufzeit. Sie sind als Ausnahme von der Benutzerabnahme erfasst. Details: [acceptance-results.md](acceptance-results.md).
2. Die installierten Windows-Läufe mit Java 21/25 fehlen. macOS und Linux ARM64 sind geprüft.
   Die CI-Matrix ist konfiguriert; `companion_ref` erlaubt eine koordinierte Companion-Revision.
   Erfolgreiche installierte Läufe erzeugen `platform-evidence.json`; der Collector führt
   diese mit Prüfsummen zusammen und weist die fehlenden Windows-Zellen aus.
3. Eine spätere Veröffentlichung muss exakt die geprüften kanonischen Artefakte verwenden.
   Abweichende ZIPs machen die vorliegenden Nachweise für eine Veröffentlichung ungültig.

Es wurde keine Veröffentlichung beauftragt oder ausgelöst. Die Benutzerabnahme ändert den
automatischen Freigabeprüfer und seine Nachweisanforderungen nicht. Die Artefakt-Hashes und
die Abnahmeentscheidung stehen in `artifact-status.json`.
