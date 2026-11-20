# Implementierungs- und Teststand

## Implementiert

- Getrennte Core-, ValueMeta-, GeoTools- und Assembly-Module mit dem gemeinsamen Plugin-Parent.
- Unveränderliche Rasterwerte, begrenzter versionierter Codec, Clone/Vergleich/Hashing ohne Pixel-I/O.
- Metadatenplanung für Clip/Reproject; blockweise Ausführung mit begrenzten Sitzungscaches.
- Lokale GeoTIFF- und öffentliche HTTP-Quellen; GeoTIFF/BigTIFF-Writer mit temporärer Nachbardatei.
- Raster Reader, Info, Clip, Reproject, Writer und Zonal Statistics im Companion-Repository.
- Mehrbandauswahl, RGB/Alpha/Palette, NoData und Scale/Offset; Quellen- und Operationsherkunft in Fehlern.
- Migration der Dateikonfigurationen, Beispielpipelines, Error-Hop-Migration und nachvollziehbares Layout.
- Getrennte ZIPs und Prüfung der gemeinsamen Klassenidentität in installiertem Hop.
- CI für Java 21/25 und Linux/macOS/Windows; Veröffentlichung nach erfolgreicher technischer CI.


[Prüfung grosser Raster](performance.md)
