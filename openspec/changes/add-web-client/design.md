## Context

- The API is complete for the client's needs (see `docs/SYSTEM_DESIGN.md`, API table):
  - the public catalog;
  - availability, booking, reading and cancelling;
  - `/me`, `/me/vehicles` and `/me/appointments`;
  - a dealership's day;
  - customer search.
  Errors are RFC 9457 problems with a stable `code` and a `requestId`.
- Authentication is Supabase Auth. The backend only verifies tokens. A browser may hold the project's
  publishable key, which is designed to be public. The secret key never reaches the client.
- The UI/UX skill `ui-ux-pro-max` (committed on `ui/ux-design`) guided the visual direction. Its
  closest palette was "B2B Service: professional navy + blue CTA". Its rules are applied: one primary
  action per screen, SVG icons rather than emoji, visible focus, reduced motion, and 375 px first.
- The contrast of the user's palette was measured, as WCAG ratios:

  | Pair | Ratio | Use |
  | --- | --- | --- |
  | white on navy `#0B2545` | 15.4 | top bar |
  | yellow `#FACC15` on navy | 10.1 | active navigation, logo |
  | navy on white | 15.4 | headings |
  | ink `#0A0A0A` on white | 19.8 | body text |
  | ocean `#0B5FAE` on white | 6.4 | links, secondary buttons, focus ring |
  | ink on yellow | 12.9 | primary button, selected slot |
  | yellow on white | **1.5** | never text or border |
  | ocean on navy | **2.4** | never a link on the top bar |

## Goals / Non-Goals

**Goals:**
- The three role journeys from the spec, in a calm, minimalist, accessible interface.
- Make the backend's guarantees visible: dealership time, idempotent confirm, a slot taken
  meanwhile, and staff-only search.
- Tests that run offline and fast, with no backend and no Supabase.

**Non-Goals:** hosting and Docker, end-to-end tests against real services, sign-up, dark mode, i18n,
rescheduling (see the proposal).

## Decisions

### 1. Stack

| Concern | Choice | Why, and the alternative rejected |
| --- | --- | --- |
| Framework | React 19 + TypeScript, built by **Vite** | A client-side app over an existing API needs no SSR. Next.js was rejected as more machinery than the job needs |
| Server state | **TanStack Query** | caching, loading and error states, invalidation after booking or cancelling. Mutations never retry automatically |
| Routing | **React Router** | deep links (`/schedule?dealershipId=2&date=2026-11-03`) and a correct back button |
| Styling | **Tailwind CSS v4** over CSS variables (the tokens of decision 6) | a consistent spacing and colour scale. No component library: a handful of hand-built components keeps the look minimal and the focus and contrast fully under control (shadcn/Radix rejected as more than needed) |
| Icons | **lucide-react** | consistent stroke SVG icons |
| Font | **Inter**, self-hosted through `@fontsource-variable/inter` | no request to a font CDN. Tabular figures for times |
| Auth | **@supabase/supabase-js** | sign-in, token refresh and sign-out handled by Supabase |
| Tests | **Vitest**, jsdom, Testing Library, user-event, **MSW** | MSW intercepts `fetch` at the network level, so the real API client code runs in tests |

Exact versions are pinned by `package-lock.json`. The client needs Node 20 or later (this machine has
Node 25).

### 2. Layout of `frontend/`

```
frontend/
  index.html  vite.config.ts  tsconfig*.json  package.json  .env.example  README.md
  src/
    main.tsx  App.tsx (routes)
    styles/tokens.css           CSS variables (decision 6), Tailwind theme
    lib/api.ts                  fetch wrapper: bearer token, problem parsing, 401 handling
    lib/supabase.ts             client from VITE_SUPABASE_URL / VITE_SUPABASE_PUBLISHABLE_KEY
    lib/time.ts                 dealership-time formatting, "today" in a zone
    lib/normalize.ts            accent-insensitive text matching
    lib/errors.ts               problem code → plain message
    auth/                       AuthProvider, RequireAuth, SignInPage, MeProvider (/me)
    layout/                     AppShell (top bar, navigation, sign out)
    features/booking/           BookingFlow (vehicle → service → dealership+date → slot → confirm)
    features/appointments/      MyAppointmentsPage
    features/schedule/          DaySchedulePage (timeline, list on narrow screens)
    features/customers/         CustomerSearch (Book for a customer)
    ui/                         Button, Field, Select, OptionCard, Alert, Dialog, Spinner, EmptyState
  test/                         MSW handlers and fixtures, test setup, render helpers
```

### 3. API client and session

- `api(path, options)` reads the current Supabase session, adds `Authorization: Bearer`, and parses
  JSON. On error it throws `ApiError { status, code, detail, requestId, errors }` from the problem
  body. A body that is not a problem becomes `INTERNAL_ERROR`, with the response's `X-Request-Id`.
- On `401` it signs out through Supabase and routes to `/sign-in?expired=1`.
- During development, `/api` is proxied by Vite to `http://localhost:8080`. The browser therefore
  sees one origin, and the backend needs no CORS configuration.
- `MeProvider` loads `GET /api/v1/me` once per session. Routes are guarded by role: a customer
  opening `/schedule` lands on their start page.

### 4. Booking flow

```
 vehicle ─▶ service ─▶ dealership + date ─▶ slot ─▶ confirm ─▶ done
 (customer: /me/vehicles;     (native <input type="date">,     (Idempotency-Key = crypto.randomUUID()
  advisor: customer search)    "today" in the dealership zone)   created on entering confirm)
```

- The state lives in the flow component. Going back and changing anything discards the key, and the
  next confirm creates a new one.
- Confirm is disabled while its request is in flight, so a double click sends one request. *Try
  again* after a network failure resends the same body with the same key, so the backend replays the
  booking rather than booking twice.
- `409 NO_AVAILABILITY` returns to the slot step, invalidates the availability query and shows "That
  time was just taken. Please choose another."
- Availability is fetched for the chosen dealership, service and date. An empty `slots` array shows
  an empty state with "Try another date".

### 5. Time display

`Intl.DateTimeFormat('en-GB', { timeZone: dealership.timeZone, hour: '2-digit', minute: '2-digit' })`,
labelled with the IANA id. The browser's zone is never used. A test runs with `TZ=UTC` and checks
that `2026-11-03T10:00:00+07:00` renders as `10:00`. "Today" for a dealership is computed in its
zone, so a London browser at 23:00 sees Saigon's next day.

### 6. Design tokens and components (minimalism)

| Token | Value | Role |
| --- | --- | --- |
| `--navy` | `#0B2545` | top bar, headings |
| `--ocean` | `#0B5FAE` | links, secondary (outline) buttons, focus ring, selected tab line |
| `--yellow` | `#FACC15` | the one primary button per screen, selected slot (with ink text) |
| `--ink` | `#0A0A0A` | body text |
| `--white` | `#FFFFFF` | page and cards |
| `--slate-700` | `#475569` | secondary text (7.6:1) |
| `--line` | `#E2E8F0` | decorative dividers only |
| `--field-border` | measured at 3:1 or more | input and slot borders |
| `--danger` | `#B91C1C` | errors and the destructive *Cancel* action only, always with an icon and text |

- Inter at 16 px with line height 1.5, a type scale of 14/16/20/24/32, a 4/8 px spacing scale and
  8 px radius. No shadows except a subtle one on dialogs.
- The focus ring is a 2 px `--ocean` outline offset by 2 px, and `--yellow` on the navy bar.
- A slot is a bordered button showing the time and "2 bays free". The selected slot is yellow with
  ink text and a 2 px ink border. Fully booked times are absent from the API, so they are not shown.
- Motion is limited to 150–200 ms opacity and transform transitions, removed under
  `prefers-reduced-motion`.
- A unit test computes the contrast ratio of every text and background token pair used, so a token
  edit cannot silently break AA.

### 7. Day schedule

- The bays and technicians come from `GET /api/v1/dealerships/{id}`, the service names from
  `GET /api/v1/service-types`, and the appointments from the day endpoint.
- From 768 px up, a CSS grid shows one row per bay. The columns are the opening hours, in steps of
  the slot length. A block's position and width come from its minutes since opening. Below 768 px,
  the same data shows as a list grouped by bay, which avoids horizontal scrolling.

### 8. Accent-insensitive filtering in the client

`normalize(s) = s.normalize('NFD').replace(/\p{M}/gu, '').replace(/đ/g, 'd').replace(/Đ/g, 'D').toLowerCase().trim()`.
Its tests reuse the backend's cases: "Lê Minh Đức", decomposed input and capitals.

### 9. Errors shown to people

`lib/errors.ts` maps each known problem `code` to a sentence:
- `NO_AVAILABILITY`, `VEHICLE_ALREADY_BOOKED`, `SLOT_CONTENTION`;
- `START_TIME_IN_PAST`, `OUTSIDE_OPENING_HOURS`;
- `APPOINTMENT_ALREADY_STARTED`, `FORBIDDEN`, `VALIDATION_FAILED`;
- and the rest.

An unknown code or a `500` shows "Something went wrong" with the `requestId`. Errors render in
`role="alert"` next to the action they concern.

### 10. Tests

- Each spec scenario has a test named after it, using MSW handlers that return the API's real
  response shapes. `supabase-js` is replaced by a small fake session module.
- Commands: `npm test` (Vitest), `npm run typecheck` (`tsc --noEmit`) and `npm run build`.
- The keyboard scenario uses `user-event` with Tab, Enter and Space only.
- A manual end-to-end check with the real backend and the demo users is a task. It depends on the
  Supabase keys in `.env` and `frontend/.env.local`.

## Concurrency

The client makes the backend's concurrency handling visible rather than adding its own. Confirm is
idempotent through one key per intent. A slot taken meanwhile (`409`) refreshes the slots. After a
booking or a cancellation, the cached lists (my appointments, the day schedule, availability) are
invalidated, so the screens show the committed state.

## Metrics and log events

No backend change, and no client analytics. Each API call already appears in the backend access log
and `http_server_requests_seconds`. Error messages show the `requestId`, so a user report can be
matched to the server log.

## Risks / Trade-offs

- [Mocked tests can drift from the real API] → The MSW fixtures copy the shapes in
  `docs/SYSTEM_DESIGN.md` and the OpenAPI document. The manual end-to-end check against the real
  backend is a task.
- [The client runs only through the Vite proxy] → Hosting the built files behind one origin, with
  nginx or by the backend, is a follow-up. Until then, `npm run dev` is the way to run it.
- [Scope is large for the deadline] → The tasks are ordered by role. Customer booking comes first,
  being the core requirement, then my appointments, the schedule and booking for a customer. Each
  group is usable on its own.
- [jsdom lacks some browser APIs, such as `<dialog>`] → A small polyfill in the test setup, and a
  manual check in a real browser.

## Migration Plan

The change is additive: the backend is untouched. Run the backend as before, then `cd frontend`,
`npm install` and `npm run dev`, and open `http://localhost:5173`.

## Open Questions

None. The palette, the stack and the role scope were decided with the user. English UI, light mode
only and errors in red are recorded as assumptions or non-goals, for review.
