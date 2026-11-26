# WazeAlert community server

Anonymous, shared police / hazard / accident reports for the WazeAlert apps. No dependencies, Node 18.2+.

```
npm test        # 8 tests
npm start       # listens on :8080 (PORT), stores to ./data/reports.json (DATA_FILE)
```

## API (JSON)

| Method & path | Body / query | Result |
|---|---|---|
| `POST /v1/reports` | `{type: POLICE\|HAZARD\|ACCIDENT, lat, lon, heading?, deviceId}` | `201 {id}`; `200 {id, merged:true}` if it confirms a nearby active report of the same type |
| `POST /v1/reports/:id/vote` | `{deviceId, vote: 1 \| -1}` ("still there" / "gone") | `{score, active}` |
| `GET /v1/reports` | `lat, lon, radius` (m, max 30000) | `{reports:[{id,type,lat,lon,heading,ageSec,seenSec,score}]}` |
| `GET /v1/risk` | `lat, lon, radius, tz` (client UTC offset, minutes) | `{score 0-100, level, reports}` police-enforcement risk at this place and time of week |
| `GET /healthz` | | `{ok:true}` |

## Rules

- Reports last 2 h (police, accident) or 4 h (hazard) after the last "still there"; confirmations refresh them.
- A report is hidden once its score (reporter and "still there" votes minus "gone" votes) reaches -2.
- One vote per device per report. A device may file one report per 15 s, 20 per hour; 120 requests/min per IP.
- History is kept 90 days and feeds the risk score (30-day half-life, extra weight for the same time of day and weekday/weekend).

## Privacy

Devices send a random ID that the server salts and hashes; no accounts, no IPs stored (rate-limit counters live in memory only).
Reports do contain a location, so the apps tell users that reports are shared.

## Deploying

Use HTTPS in production (the apps refuse plain HTTP except to the emulator host). Any container host works:
`docker build -t wazealert-server . && docker run -p 8080:8080 -v wa-data:/data wazealert-server`
Put it behind a TLS-terminating proxy (Render, Fly.io, Railway, Cloud Run, Caddy...). Keep a single instance, because storage is a local file.
For a larger community, replace the JSON file with a real database and add moderation tools.
