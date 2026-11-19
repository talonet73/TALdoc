# TALdoc AI

App Android che **legge qualsiasi documento**, lo analizza **offline** e risponde alle tue richieste.
Tutto resta sul telefono: l'app non ha nemmeno il permesso di accedere a internet.

## Cosa fa

| Funzione | Descrizione |
|---|---|
| **Importa** | PDF, Word (DOC, DOCX), Excel (XLS, XLSX), TXT, CSV, ODT/ODS, RTF, immagini. Anche da «Apri con» / «Condividi» di altre app. |
| **Scansiona / Foto** | Scanner documenti con ritaglio automatico (ML Kit) o fotocamera, poi OCR offline. |
| **Domanda** | Box per domande libere: «dammi l'indirizzo di SSI FACTORY», «qual è il totale?», «cosa dice il codice 8002», «quanto costa la luce a febbraio» (lettura riga × colonna nelle tabelle). |
| **Chiarimenti** | Se la domanda è vaga o ambigua (es. più importi possibili, parola non presente) l'app chiede di precisare e propone le opzioni. |
| **Riassunto** | Punti chiave + dati chiave (importi, date, email, nomi). Con Gemma il riassunto è discorsivo. |
| **Trascrizione** | Testo completo, pagina per pagina, copiabile e condivisibile. |
| **Cerca** | Ricerca esatta con suggerimenti per errori di battitura/OCR. |
| **Confronta** | Differenze tra due documenti: righe modificate, parti mancanti, parti aggiunte, valori cambiati. |
| **Parti mancanti** | Pagine saltate («pag. 2 di 5»), numerazioni interrotte (Art. 1, 2, 4…), campi da compilare vuoti, pagine illeggibili. |
| **Verifica conti** | Totali e subtotali, quantità × prezzo, IVA sull'imponibile, totali di colonna in Excel/CSV: segnala i conti che non tornano e la differenza. |
| **Esporta** | PDF, TXT, CSV. |

## Intelligenza artificiale sul telefono (Gemma)

Senza modello l'app usa il suo **motore offline** (ricerca BM25 tollerante agli errori OCR, estrazione mirata di indirizzi, importi, date, IVA, codici fiscali, IBAN, email, telefoni).

Con **Gemma 3 1B** (≈530 MB, gira anche su telefoni di fascia media) risposte e riassunti diventano discorsivi:

1. Menu ⋮ → *Modello AI…* → **Scarica Gemma**: si apre la pagina ufficiale [litert-community/Gemma3-1B-IT](https://huggingface.co/litert-community/Gemma3-1B-IT). Accetta la licenza e scarica `gemma3-1b-it-int4.litertlm`.
2. Torna in TALdoc → **Carica modello** → scegli il file scaricato.

Il modello gira con [LiteRT-LM](https://ai.google.dev/edge/litert-lm) su CPU (GPU opzionale). Funziona qualsiasi modello `.litertlm` (es. Qwen, Gemma 3n per telefoni con più RAM).
Anche con l'AI attiva, sotto ogni risposta compare il **riscontro diretto nel testo** con la fonte (documento e pagina), così puoi verificare i numeri.

## Download

Ultima versione: **[Releases](https://github.com/talonet73/TALdoc/releases/latest)** → `TALdoc-AI-x.y.z.apk`.
Installazione: apri l'APK sul telefono e consenti «Installa app sconosciute». Android 10 o superiore.

## Sviluppo

- Kotlin + Jetpack Compose, `minSdk 29`.
- Logica di analisi in `app/src/main/java/it/tal/taldoc/core` (Kotlin puro, testata con JUnit: `gradle testDebugUnitTest`).
- Lettori DOC/XLS (OLE2/BIFF8), DOCX/XLSX/ODT/ODS/RTF scritti da zero, senza dipendenze pesanti; PDF con PdfBox-Android (testo nativo) + OCR ML Kit per le pagine scansionate.
- Ogni push compila ed esegue i test su GitHub Actions; ogni push su `main` pubblica (o aggiorna) la release `v<versionName>` con l'APK firmato: per una nuova versione basta aumentare `versionName`/`versionCode`.

### Firma dell'APK

Per default l'APK è firmato con la chiave del progetto (`keystore/taldoc.jks`), così gli aggiornamenti si installano sopra la versione precedente.
Per usare una chiave privata aggiungi nei *Secrets* del repository: `TALDOC_KEYSTORE_B64` (keystore in base64), `TALDOC_KEYSTORE_PASSWORD`, `TALDOC_KEY_ALIAS`, `TALDOC_KEY_PASSWORD`.

## Privacy

Nessun permesso INTERNET (rimosso esplicitamente anche se richiesto da librerie). I documenti restano nella cache privata dell'app; «Esci e cancella» elimina la sessione.
