# Grosse Raster mit begrenztem Heap prüfen

Mit JDK 21 und den Maven-Einstellungen aus `AGENTS.md`:

```sh
JAVA_TOOL_OPTIONS=-Xmx512m mvn -s "$MAVEN_SETTINGS" -B -ntp \
  -pl hop-raster-geotools -am test -Dtest=RasterLargeChainTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Draster.largeChain=true
```

Der Test erzeugt ein gekacheltes RGB-Raster mit 16384 × 16384 Pixeln und führt
Clip, Reprojektion und Schreiben in einer JVM mit 512 MiB Heap aus. Er prüft
das Ausgabegitter, Nearest-Neighbour-Werte an Kachelgrenzen und die Freigabe
der Reader-Sitzungen. Das temporäre Testverzeichnis benötigt Platz für Eingabe
und Ausgabe.

Die regulären Maven-Tests prüfen Rasterwerte, NoData, Farbbänder, Fenster,
HTTP-Range-Zugriffe, COG-Ausgabe und das Aufräumen bei Fehlern. Die CI führt
zusätzlich die installierten Vector/Raster-Pipelines aus.
