# Service Scheduler web client

A React client for the Unified Service Scheduler backend. People sign in with Supabase Auth, and
the client calls the backend's `/api/v1` with the access token. Each role gets its own pages:

| Role | Pages |
| --- | --- |
| Customer | Book a service (vehicle → service → dealership and date → time → confirm), My appointments (cancel) |
| Advisor | Day schedule of their dealership, Book for a customer (search by name or e-mail) |
| Admin | As an advisor, for any dealership |

Behaviour is specified in `openspec/changes/add-web-client/specs/web-client/spec.md`, and the
decisions are in that change's `design.md`.

## Setup

Node 20 or later.

```bash
cd frontend
npm ci
cp .env.example .env.local
```

In `.env.local`, set `VITE_SUPABASE_URL` and `VITE_SUPABASE_PUBLISHABLE_KEY`, both from the Supabase
project's API settings. The publishable key is meant for browsers. Never put the secret key here:
everything prefixed `VITE_` ends up in the bundle. `.env.local` is git-ignored. Without it, the app
starts but sign-in reports that Supabase is not configured.

## Running with the backend

```bash
./mvnw spring-boot:run          # in the repository root: the backend on http://localhost:8080
npm run dev                     # in frontend/: http://localhost:5173
```

The dev server proxies `/api` to `http://localhost:8080`, so the browser sees one origin and no
CORS setup is needed. Sign-in needs Supabase users whose `app_metadata` links them to a customer or a
dealership; `scripts/supabase-demo-users.py` creates one per role (see its header).

## Tests

```bash
npm test             # Vitest + Testing Library + MSW, offline: no backend, no Supabase
npm run typecheck
npm run build        # type check, then the production bundle in dist/
```

- Tests render the real app with a `MemoryRouter`. MSW answers `/api/v1` and fails any unhandled
  request. A fake `AuthClient` replaces Supabase.
- `TZ=UTC`, and `Date` is fixed at `2026-11-02T09:00:00Z`. Only `Date` is faked, so timers and
  debouncing run for real. Times are therefore checked in each dealership's own zone, whatever the
  machine's zone is.
- Override a handler with `mockApi(account, ...handlers)`; its overrides win over the defaults.
- App-level `describe` blocks are named after the spec's requirements, and their tests after its
  scenarios. Unit tests (`ui/`, `lib/`, `styles/`) are named after what they test.

## Layout

```
src/
  styles/     tokens.ts (palette + contrast test pairs), index.css (Tailwind @theme, focus ring)
  ui/         Button, TextField/SelectField, OptionCard, Alert, Dialog, Spinner/EmptyState
  lib/        api client (ApiError), error messages, time zones, accent folding, types
  auth/       AuthProvider, SignInPage, RequireAuth, the Supabase AuthClient
  account/    MeProvider (/api/v1/me), role guards
  layout/     AppShell: top bar, navigation, sign out
  features/   booking, appointments, schedule, customers
  test/       MSW server, handlers, fixtures, renderApp
```

## Design rules

- Colours come only from the tokens: navy, ocean blue, yellow, ink, white, and the greys `mist`,
  `slate`, `field` and `line`. Red (`danger`) is used for errors and cancelling only.
- Yellow is a background, never text or a border on white. It marks the one primary action per
  screen and the selected option; yellow text appears only on navy.
- `tokens.test.ts` checks every text pair in use for 4.5:1 contrast and every border or focus pair
  for 3:1. It also checks that `index.css` uses the same values as `tokens.ts`.
- Every control has a visible label and the focus ring: ocean, or yellow on navy. Errors are shown
  in `role="alert"` in plain words, with the request id when the cause is unknown.
