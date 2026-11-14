# Nova — Standalone Realtime AI Voice Assistant

A complete small web app using OpenAI Realtime API + WebRTC, Node/Express, and a vanilla HTML/CSS/JS frontend.

## Requirements
- Node.js 20+
- OpenAI API account with Realtime API access and billing configured
- Microphone, current Chrome/Edge/Safari, localhost or HTTPS

## Local setup
```bash
npm install
cp .env.example .env
# edit .env and set OPENAI_API_KEY
npm start
```
Visit http://localhost:3000, allow microphone access, and click Start talking.

## Docker
```bash
docker build -t nova-voice .
docker run --rm -p 3000:3000 --env-file .env nova-voice
```

## Deployment
Deploy to a Node.js web service (Render, Railway, Fly.io, VPS or similar). Configure `npm install` build and `npm start` run command. Set OPENAI_API_KEY as a private environment variable and use HTTPS. Do not expose the API key in frontend code. If the hosting platform supports only static sites or serverless functions, use a separate Node backend or adapt the `/api/session` route.

## Important security and production notes
- `/api/session` has basic per-IP rate limiting but no authentication. Add login, authorization, per-user quotas, and anti-abuse controls before publishing publicly.
- The server never stores audio or transcripts; audio is processed by the upstream provider. Review provider retention and privacy terms separately.
- The session token is short-lived. No key should be committed to Git.
- Realtime usage is billed by the API provider. Set budgets and usage alerts.
- Transcript event formats and realtime model availability can change. See https://platform.openai.com/docs/guides/realtime for current guidance.
- This prototype does not persist history, perform external actions, or guarantee full interruption handling across browsers.
- For multiple instances use a shared rate limiter (e.g. Redis), not only in-memory IP limits.
