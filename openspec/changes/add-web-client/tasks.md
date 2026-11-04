## 1. Scaffold and design tokens (tests first where behaviour exists)

- [x] 1.1 Scaffold `frontend/` with Vite, React and TypeScript, and set up:
  - Tailwind v4, TanStack Query, React Router, `supabase-js`, `lucide-react` and Inter;
  - Vitest, Testing Library, user-event and MSW;
  - the scripts `dev`, `build`, `test` and `typecheck`;
  - the `/api` proxy to `http://localhost:8080`;
  - `.env.example`, and `.gitignore` entries for `node_modules`, `dist` and `.env.local`.

  Check that `npm test`, `npm run typecheck` and `npm run build` run on the empty app
- [x] 1.2 Write the token contrast test (design decision 6): every text and background pair used is at least 4.5:1, borders are at least 3:1, and yellow is never paired with white. Then add `tokens.css` and make the test pass
- [x] 1.3 Write component tests for `Button` (primary, secondary, danger; disabled while busy), `Field` (visible label, error announced), `OptionCard` (keyboard selection) and `Dialog` (focus trap, Escape). Then implement the `ui/` components

## 2. API client and session (tests first)

- [x] 2.1 Test `lib/api.ts` against MSW:
  - it sends the bearer token;
  - it parses a problem into `ApiError` (`code`, `detail`, `requestId`, `errors`);
  - a body that is not a problem becomes `INTERNAL_ERROR` with the `X-Request-Id`;
  - a `401` signs out and routes to sign-in.
- [x] 2.2 Implement `lib/api.ts`, `lib/errors.ts` and `lib/supabase.ts`
- [x] 2.3 Write tests for "Sign-in and session": successful sign-in, wrong credentials, signed-out visitor, expired session
- [x] 2.4 Implement `AuthProvider`, `SignInPage`, `RequireAuth` and `MeProvider`

## 3. Navigation (tests first)

- [x] 3.1 Write tests for "Role-based start page and navigation": customer start page, advisor start page, account without a role, and the current page marked in the navigation
- [x] 3.2 Implement `AppShell` (top bar, navigation, sign out) and the role-guarded routes

## 4. Booking a service (tests first)

- [x] 4.1 Unit-test `lib/time.ts`, with `TZ=UTC`, for dealership-zone labels and "today" in a zone, and `lib/normalize.ts` with the backend's accent cases
- [x] 4.2 Write tests for "Booking a service" and "Choosing a service type", one per scenario. They include the double click, the retry with the same `Idempotency-Key`, the slot taken meanwhile, and filtering without accents
- [x] 4.3 Implement `BookingFlow` and its steps (design decision 4); make 4.1 and 4.2 pass

## 5. My appointments (tests first)

- [x] 5.1 Write tests for "My appointments": upcoming and past, cancel with confirmation, too late to cancel
- [x] 5.2 Implement `MyAppointmentsPage`, and invalidate cached lists after a cancellation

## 6. Day schedule (tests first)

- [x] 6.1 Write tests for "Day schedule": the advisor sees the day, empty day, the admin picks a dealership, and date navigation
- [x] 6.2 Implement `DaySchedulePage`: a timeline from 768 px up and a list grouped by bay below (design decision 7)

## 7. Booking for a customer (tests first)

- [x] 7.1 Write tests for "Booking for a customer": find and book for a customer, too short to search, nobody found
- [x] 7.2 Implement `CustomerSearch` (search once two characters are typed and typing pauses) and hand the choice to `BookingFlow`

## 8. Accessibility and finish

- [x] 8.1 Write tests for "Accessible, minimalist presentation": a keyboard-only booking, errors announced with `role="alert"`, and an unknown error showing the request id
- [x] 8.2 Fix any gap 8.1 finds
- [ ] 8.3 Check manually in a real browser, and record the results in `docs/AI_LOG.md`:
  - 375 px width with no horizontal scroll;
  - reduced motion;
  - focus visible on every control;
  - the skill's pre-delivery checklist.

## 9. Documentation

- [x] 9.1 Write `frontend/README.md`: setup, `.env.local`, running with the backend, tests
- [x] 9.2 Update the documentation:
  - `docs/SYSTEM_DESIGN.md`:
    - a web client section and its place in the architecture diagram;
    - the palette and contrast table;
    - the assumptions.
  - the root README run instructions, if the README exists on this branch;
  - `AGENTS.md`: frontend commands and rules (tokens only, no yellow text on white, tests with MSW);
  - `openspec/config.yaml`, and `docs/AI_LOG.md`.

## 10. Verification

- [x] 10.1 Run `npm test`, `npm run typecheck` and `npm run build` in `frontend/` and quote the summaries; every test passes
- [x] 10.2 Run `./mvnw clean test` to confirm the backend is untouched and green
- [x] 10.3 Map every scenario of `specs/web-client` to a passing test and list any gap
- [ ] 10.4 Manual end-to-end with the real backend and the demo users (needs the Supabase keys in `.env` and `frontend/.env.local`); if the keys are not available, record that the check is pending
- [x] 10.5 Run `openspec validate add-web-client --strict` and `openspec validate --all --strict`
