## 2026.39.1 — 2026-09-24

### ✨ Features
- honour the trade veto and name traded items in the ledger (`0c0d2c1`)

### 🐛 Fixes
- keep ledger item text safe for latin1 MySQL tables (`d604c99`)
- save toggles.yml through a temp file and atomic move (`56e448c`)
- record enchantments and container contents in trades.log (`10e279f`)
- keep every pending request and forget them on quit (`8b7ea56`)
- tie expected-close flags to the window they were set for (`7cc1b97`)
- refuse confirmations for a moment after either offer changes (`d313d87`)
- end coin prompts whose answer can never arrive (`0545ef2`)
- search shulker boxes and bundles for blocked items (`a9ccaf6`)
- save player data around escrow so a crash cannot duplicate items (`57401ba`)
- keep the sign prompt off block entities and blocks already in use (`31cb61e`)

### ⚡ Performance
- write both sides of a change in one flush (`5b0a009`)

### 📝 Documentation
- add MIT license (`0ee2d4a`)

## 2026.39.0 — 2026-09-23

### 🔧 Other
- paper-api 26.2.build.121-stable -> 26.2.build.123-stable (`594da57`)

## 2026.37.0 — 2026-09-13

### 🐛 Fixes
- retain unresolved payment settlements and prevent unsafe retries (`3780e1e`)

## 2026.36.1 — 2026-09-06

### 🐛 Fixes
- release at 10:00 Central or later, not exactly 10:00 (`af58992`)

## 2026.36.0 — 2026-09-06

### ✨ Features
- report anonymous usage to bStats (`050f95d`)
- request toggle, item blacklist, and a live settle countdown (`e45ac9a`)

### 🐛 Fixes
- reject NaN and Infinity as coin offers (`28f2b81`)

### 📝 Documentation
- state the Paper 26.2-or-newer requirement (`beb76d6`)

## 2026.32.0 — 2026-08-07

### ✨ Features
- take the coin amount on a sign instead of in chat (`0be507f`)
- player-to-player trading that cannot be changed after both sides agree (`6b6691d`)

