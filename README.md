# VOID Files

Ein minimalistischer Android-Dateimanager im Nothing-Stil.

## Funktionen
- Vollständige Dateiverwaltung: Kopieren, Verschieben, Umbenennen, Löschen, neue Ordner/Dateien, Mehrfachauswahl, Suche, Eigenschaften, Teilen, „Öffnen mit“
- Papierkorb mit Wiederherstellen
- Archive: ZIP (auch AES-verschlüsselt), 7z, TAR, TAR.GZ/BZ2/XZ, GZ, BZ2, XZ, RAR (bis RAR4) entpacken; ZIP erstellen (optional mit Passwort)
- Designs: Schwarz (AMOLED), Graphit, Stahl, Papier, Weiß, System, Material You; sieben Akzentfarben; Dot-Matrix-Überschriften
- Querformat: zwei Ordner nebeneinander, Dateien per Pfeil kopieren/verschieben
- Proton Drive: als Cloud-Ordner einbinden (sofern die Proton-App den Android-Speicherzugriff anbietet) und „An Proton Drive senden“

## APK herunterladen
Jeder Push baut automatisch eine APK – zu finden unter **Releases** bzw. im Actions-Lauf als Artifact.

## Signatur
Standardmäßig wird mit dem Schlüssel in `signing/` signiert, damit Updates sich über die alte Version installieren lassen.
Für einen privaten Schlüssel die Secrets `VOID_KEYSTORE_BASE64`, `VOID_KEYSTORE_PASSWORD`, `VOID_KEY_ALIAS` und `VOID_KEY_PASSWORD` im Repository hinterlegen.
