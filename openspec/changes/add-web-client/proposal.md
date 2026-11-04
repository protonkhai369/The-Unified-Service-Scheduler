## Why

The backend serves every booking need, but people can reach it only through Swagger UI, cURL or
Postman. The user decided to build a real web client, so that the assessment demo shows the flows as
customers and service advisors would live them:
- signing in;
- picking your own vehicle;
- seeing the bookable times before booking;
- a dealership's day at a glance;
- booking on a customer's behalf.

The backend remains the fully implemented layer the brief asks for. The client exercises its
guarantees visibly:
- a slot taken meanwhile answers `409` and the slots refresh;
- a double-clicked "Confirm" books once, through the idempotency key;
- times show in the dealership's own time zone.

This serves Scenario A requirements 1 to 3 end to end, and the "communication and presentation"
criterion.

## What Changes

- A new single-page web application in `frontend/`: React with TypeScript, built by Vite.
  - Supabase Auth handles sign-in (e-mail and password), through `supabase-js` with the project's
    publishable key.
  - Every API call goes through the Vite dev server's proxy (`/api` → `http://localhost:8080`), so the
    backend needs no CORS change and stays untouched.
- Screens by role, after sign-in. `GET /api/v1/me` picks the landing page and the navigation:
  - **Customer**:
    - *Book a service*: vehicle, then service, then dealership and date, then a free slot, then
      confirm;
    - *My appointments*: upcoming and past, with cancel.
  - **Advisor**:
    - *Day schedule* of their dealership: a timeline of the bays across opening hours;
    - *Book for a customer*: a customer search (accent- and typo-tolerant), then the same booking
      flow.
  - **Admin**: as an advisor, with a dealership picker on the schedule.
- Minimalist visual design in navy, ocean blue, yellow, black and white. Navy is the brand surface,
  ocean blue the interactive accent, and yellow the one primary action per screen and the selected
  slot. Contrast is measured to WCAG AA, and yellow is never used as text on white.
- Accessibility and responsiveness:
  - keyboard operable, with a visible focus ring;
  - every field labelled;
  - errors announced;
  - reduced motion respected;
  - usable at 375 px wide.
- Tests: Vitest with Testing Library, and the API mocked with MSW at the network level. There is
  also a type check and a production build. `npm test` runs without a backend or Supabase.
- Docs: how to run the client, in `frontend/README.md` and in `docs/SYSTEM_DESIGN.md`.

## Capabilities

### New Capabilities

- `web-client`: what users can do in the browser, per role, and how the client presents booking
  rules, errors, time zones and accessibility.

### Modified Capabilities

None. The client uses the existing API as specified.

## Non-goals

- Hosting the built client (Docker or nginx), production deployment, and CORS.
- End-to-end browser tests against a running backend and Supabase. The API is mocked in tests, and a
  manual end-to-end check is listed in the tasks.
- Sign-up, password reset and account management. The demo users are created by
  `scripts/supabase-demo-users.py`.
- Dark mode, internationalisation (the UI is in English, like the API and the assessment), and
  offline use.
- Rescheduling, and editing customers, vehicles or catalog data.
- Server-side service search. The client filters the service list locally, ignoring accents.

## Assumptions

Also recorded in `docs/SYSTEM_DESIGN.md`:

- The client is a demonstration and operations tool next to the API, not a replacement for the API
  contract. Swagger UI stays the reference.
- Times are shown in the dealership's time zone and labelled with its IANA id, for example
  "10:00 (Asia/Ho_Chi_Minh)". They are never converted to the browser's zone.
- One `Idempotency-Key` (a UUID) is created per booking intent, that is, per confirm screen, and
  reused when the same booking is retried.

## Impact

- New folder `frontend/`, which holds its own `package.json`, lock file, config, sources, tests and
  README.
- `.gitignore`: `frontend/node_modules/`, `frontend/dist/` and `frontend/.env.local`. The Supabase URL
  and publishable key live only in that git-ignored file.
- Docs: `docs/SYSTEM_DESIGN.md` (web client section and the architecture diagram), the root README
  run instructions, `AGENTS.md`, `openspec/config.yaml` and `docs/AI_LOG.md`.
- No change to the backend, the database or the API.
- Prerequisite: the branch must contain `add-self-service-queries` and `add-customer-search`, which
  means merging `feat/customer-search` into `main`, then `main` into `ui/ux-design`.
