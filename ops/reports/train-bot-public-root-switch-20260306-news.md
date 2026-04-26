# Train Bot Public Root Switch Update

Date: 2026-03-06
Service: `train-bot.jolkins.id.lv`
Status: Implemented locally, not deployed yet

## English

### Summary

We have completed the code changes for the Train Bot public web entrypoint update.

The main public page at `https://train-bot.jolkins.id.lv/` is now set up to become the station-search landing page instead of the departures dashboard. The previous main page is being preserved and moved to `https://train-bot.jolkins.id.lv/departures`. The existing `/stations` route remains available as a compatibility alias.

### What changed

- `/` is now intended to open public station search
- `/departures` is now intended to host the previous public dashboard
- `/stations` remains available for backward compatibility
- public navigation now clearly separates:
  - station search
  - departures dashboard
  - individual train status pages
- readiness checks were updated to validate both the root page and the new `/departures` page

### Why this matters

This makes the public entrypoint more practical for riders. Station-based lookup is the faster starting point for most users who want to check departures from a specific station, while the departures dashboard remains available as a separate public view.

### Current status

- implementation: complete in the repo
- tests: passed for `internal/web` and `internal/i18n`
- shell validation: passed for the production readiness script
- deployment: not done yet

### Next step

The remaining work is deployment and post-deploy verification to confirm:

- `https://train-bot.jolkins.id.lv/` serves station search
- `https://train-bot.jolkins.id.lv/departures` serves the public departures dashboard
- `https://train-bot.jolkins.id.lv/stations` still works as an alias

### Short version for news channel

Train Bot public web routing update is ready in code but not deployed yet.

After deployment, `train-bot.jolkins.id.lv` will open station search by default, while the previous main page will remain available at `/departures`. The old `/stations` path will keep working for compatibility. Targeted web and i18n tests have passed.

## Latviski

### Kopsavilkums

Esam pabeiguši koda izmaiņas Train Bot publiskās tīmekļa ieejas lapai.

Publiskā galvenā lapa `https://train-bot.jolkins.id.lv/` tagad ir sagatavota, lai pēc izvietošanas pēc noklusējuma atvērtu staciju meklēšanu, nevis reisu paneli. Iepriekšējā galvenā lapa tiek saglabāta un pārvietota uz `https://train-bot.jolkins.id.lv/departures`. Esošais `/stations` ceļš paliek pieejams saderībai.

### Kas mainīts

- `/` turpmāk paredzēts publiskajai staciju meklēšanai
- `/departures` turpmāk paredzēts iepriekšējam publiskajam reisu panelim
- `/stations` paliek pieejams atpakaļsaderībai
- publiskajā navigācijā tagad skaidri atdalīti:
  - staciju meklēšana
  - reisu panelis
  - atsevišķa reisa statusa lapa
- gatavības pārbaudes papildinātas, lai pārbaudītu gan saknes lapu, gan jauno `/departures` lapu

### Kāpēc tas ir svarīgi

Tas padara publisko ieejas punktu lietotājiem praktiskāku. Meklēšana pēc stacijas vairumam pasažieru ir ātrākais sākuma punkts, ja jāatrod izbraukumi no konkrētas stacijas, bet reisu panelis joprojām paliek pieejams kā atsevišķs publisks skats.

### Pašreizējais statuss

- ieviešana: pabeigta repozitorijā
- testi: `internal/web` un `internal/i18n` izieta veiksmīgi
- shell validācija: produkcijas gatavības skriptam izieta veiksmīgi
- izvietošana: vēl nav veikta

### Nākamais solis

Atliek izvietošana un pārbaude pēc izvietošanas, lai apstiprinātu:

- `https://train-bot.jolkins.id.lv/` rāda staciju meklēšanu
- `https://train-bot.jolkins.id.lv/departures` rāda publisko reisu paneli
- `https://train-bot.jolkins.id.lv/stations` joprojām darbojas kā saderības ceļš

### Īsā versija ziņu kanālam

Train Bot publiskās tīmekļa maršrutēšanas atjauninājums kodā ir gatavs, bet vēl nav izvietots.

Pēc izvietošanas `train-bot.jolkins.id.lv` pēc noklusējuma atvērs staciju meklēšanu, bet iepriekšējā galvenā lapa paliks pieejama zem `/departures`. Vecais `/stations` ceļš turpinās darboties saderībai. Mērķētie tīmekļa un i18n testi ir izieti veiksmīgi.
