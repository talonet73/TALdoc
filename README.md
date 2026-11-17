# TALdoc AI

Android One-Shot — prima versione tecnica.

Funzioni implementate: splash ottanio con progresso reale di inizializzazione, logo in alto a sinistra, importazione multipla di PDF e immagini, scatto con fotocamera di sistema, OCR stampati offline, ricerca lessicale sui documenti della sessione, export PDF/CSV e cancellazione esplicita dei dati temporanei.

Non ancora implementati: modello LLM locale, download automatico modelli, OCR manoscritti specializzato, analisi affidabile di tabelle complesse e RAG semantico. Questa build NON è la versione finale delle specifiche.

## Compilazione
GitHub Actions > Android APK > Artifacts > TALdoc-debug-apk.

## Privacy
Nessun permesso INTERNET. I file importati sono copiati nella cache privata. «Esci e cancella» elimina la sessione, senza cancellare gli originali. Eventuali residui sono eliminati al riavvio.
