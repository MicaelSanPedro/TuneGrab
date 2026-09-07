# TuneGrab não usa minificação no build de release (isMinifyEnabled = false).
# Caso habilite, mantenha aqui as regras de keep necessárias.
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
