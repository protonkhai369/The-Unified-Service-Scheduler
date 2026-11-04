#!/usr/bin/env python3
"""Live race demo: fires parallel booking requests at the running backend and renders an HTML timeline.

Shows on the real stack (backend + its database) what the concurrency and idempotency tests assert:
  1. capacity        3 vehicles race for an oil change at Saigon (2 bays, 2 technicians)
  2. one technician  3 vehicles race for a brake service at Saigon (only one technician qualifies)
  3. one vehicle     6 requests with different keys book the same vehicle at London
  4. idempotency     8 requests with the SAME key and body (a double click, or a retry after a timeout)
  5. sequential edge cases of idempotency: reused key, other offset notation, replay after cancelling,
     no key at all
and finally reads the day back and checks that no bay, technician or vehicle is double-booked.

Each race holds its requests at a barrier and releases them together; each bar in the report runs from
sending a request to receiving its response.

Usage:  DEMO_USER_PASSWORD='...' python scripts/race-demo.py [--date YYYY-MM-DD] [--out FILE]
                                                             [--lock-timeout LABEL]
Reads:  SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY from the environment or .env; SCHEDULER_API_URL
        (default http://localhost:8080); DEMO_USER_PASSWORD from the environment only.
Needs:  Python 3.9+, standard library only, the backend running, and the demo users
        (scripts/supabase-demo-users.py).

WARNING: the demo owns one day (default: 30 days from today). Before it starts it cancels every
confirmed appointment on that date at both demo dealerships, so it can be run again.
"""

from __future__ import annotations

import argparse
import html
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

SAIGON_OFFSET = timezone(timedelta(hours=7))
ROOT = Path(__file__).resolve().parent.parent


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--date", default=(datetime.now(SAIGON_OFFSET).date() + timedelta(days=30)).isoformat())
    parser.add_argument("--out", default=str(ROOT / "target" / "race-demo" / "race-report.html"))
    parser.add_argument("--lock-timeout", default="3s (default)",
                        help="label for the report: the backend's scheduler.booking-lock-timeout")
    args = parser.parse_args()
    sys.stdout.reconfigure(errors="replace")

    settings = read_dotenv(ROOT / ".env")
    supabase_url = setting("SUPABASE_URL", settings).rstrip("/")
    publishable_key = setting("SUPABASE_PUBLISHABLE_KEY", settings)
    password = os.environ.get("DEMO_USER_PASSWORD", "")
    if not supabase_url or not publishable_key or not password:
        return fail("Set SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY (in .env or the environment) and DEMO_USER_PASSWORD.")
    api = Api(setting("SCHEDULER_API_URL", settings) or "http://localhost:8080")
    reply = api.call("POST", f"{supabase_url}/auth/v1/token?grant_type=password", absolute=True,
                     body={"email": "scheduler.admin@example.com", "password": password},
                     headers={"apikey": publishable_key})
    if reply["status"] != 200:
        return fail(f"Sign-in as the demo admin failed ({reply['status']}). Run scripts/supabase-demo-users.py first.")
    api.token = reply["body"]["access_token"]

    demo = Demo(api, args.date)
    report = demo.run()
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(render(report, args.date, args.lock_timeout), encoding="utf-8")
    print_summary(report)
    print(f"report: {out}")
    return 0


class Api:
    def __init__(self, base: str):
        self.base = base.rstrip("/")
        self.token = None

    def call(self, method, path, body=None, headers=None, absolute=False):
        request = urllib.request.Request(path if absolute else self.base + path, method=method,
                                         data=json.dumps(body).encode() if body is not None else None)
        request.add_header("Content-Type", "application/json")
        if self.token and not absolute:
            request.add_header("Authorization", f"Bearer {self.token}")
        for key, value in (headers or {}).items():
            request.add_header(key, value)
        sent = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                status, raw, response_headers = response.status, response.read(), response.headers
        except urllib.error.HTTPError as error:
            status, raw, response_headers = error.code, error.read(), error.headers
        except urllib.error.URLError as error:
            sys.exit(f"Cannot reach {request.full_url}: {error.reason}. Is the backend running?")
        received = time.perf_counter()
        try:
            payload = json.loads(raw) if raw else None
        except ValueError:
            payload = raw.decode(errors="replace")
        return {"status": status, "body": payload, "headers": dict(response_headers), "sent": sent, "received": received}


class Demo:
    def __init__(self, api: Api, day: str):
        self.api, self.day, self.run_id = api, day, datetime.now().strftime("%H%M%S")
        dealerships = {d["name"]: d["id"] for d in api.call("GET", "/api/v1/dealerships")["body"]["items"]}
        self.saigon, self.london = dealerships["Saigon Auto Centre"], dealerships["Riverside Motors London"]
        self.services = {s["code"]: s["id"] for s in api.call("GET", "/api/v1/service-types")["body"]["items"]}
        self.vehicles = {}
        for query in ("emma.wilson", "minh.duc"):
            for customer in api.call("GET", f"/api/v1/customers?q={query}")["body"]["items"]:
                for vehicle in customer["vehicles"]:
                    self.vehicles[vehicle["model"]] = (customer["id"], vehicle["id"])
        if {"Golf", "Corolla", "Focus"} - self.vehicles.keys():
            sys.exit("Demo vehicles Golf, Corolla and Focus not found. Is the demo data loaded?")

    def schedule(self, dealership):
        return self.api.call("GET", f"/api/v1/dealerships/{dealership}/appointments?date={self.day}")["body"]["items"]

    def metrics(self):
        values = {}
        for line in (self.api.call("GET", "/actuator/prometheus")["body"] or "").splitlines():
            if line.startswith("scheduler_bookings_total{"):
                values[line.split('outcome="')[1].split('"')[0]] = float(line.rsplit(" ", 1)[1])
            elif line.startswith("scheduler_booking_conflicts_total"):
                values["lost_race_conflicts"] = float(line.rsplit(" ", 1)[1])
        return values

    def start_at(self, dealership, service, local_time):
        query = urllib.parse.urlencode({"serviceTypeId": self.services[service], "date": self.day})
        slots = self.api.call("GET", f"/api/v1/dealerships/{dealership}/availability?{query}")["body"].get("slots", [])
        start = next((s["startTime"] for s in slots if s["startTime"][11:16] == local_time), None)
        if start is None:
            sys.exit(f"{local_time} is not free for {service} on {self.day}. Pick another --date.")
        return start

    def booking(self, dealership, model, service, start):
        customer, vehicle = self.vehicles[model]
        return {"dealershipId": dealership, "customerId": customer, "vehicleId": vehicle,
                "serviceTypeId": self.services[service], "startTime": start}

    def book(self, body, key=None):
        return self.api.call("POST", "/api/v1/appointments", body, {"Idempotency-Key": key} if key else None)

    def race(self, requests):
        barrier, results = threading.Barrier(len(requests)), [None] * len(requests)

        def worker(index, label, body, key):
            barrier.wait()
            results[index] = summarize(label, key, self.book(body, key))

        threads = [threading.Thread(target=worker, args=(i, *request)) for i, request in enumerate(requests)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        return results

    def run(self):
        for dealership in (self.saigon, self.london):
            for appointment in self.schedule(dealership):
                if appointment["status"] == "CONFIRMED":
                    self.api.call("POST", f"/api/v1/appointments/{appointment['id']}/cancel")
        starts = {name: self.start_at(*where) for name, where in {
            "saigon-oil": (self.saigon, "OIL_CHANGE", "10:00"), "saigon-brake": (self.saigon, "BRAKE_SERVICE", "14:00"),
            "london-oil-0900": (self.london, "OIL_CHANGE", "09:00"), "london-oil-1200": (self.london, "OIL_CHANGE", "12:00"),
            "london-oil-1500": (self.london, "OIL_CHANGE", "15:00")}.items()}
        before, sections, key = self.metrics(), [], lambda name: f"race-{self.day}-{self.run_id}-{name}"
        three = ("Golf", "Corolla", "Focus")

        r = self.race([(m, self.booking(self.saigon, m, "OIL_CHANGE", starts["saigon-oil"]), key(f"1-{m}")) for m in three])
        ok = [x for x in r if x["status"] == 201]
        sections.append(section(
            "1 · Capacity: 3 vehicles race for an oil change at 10:00, Saigon",
            "Saigon has 2 bays and 2 technicians who do oil changes, so at most 2 of the 3 can be booked.", r, [
                check("Never more than 2 bookings (the capacity)", len(ok) <= 2),
                check("Every failure is a 409 NO_AVAILABILITY or SLOT_CONTENTION and persisted nothing",
                      failed_with(r, {"NO_AVAILABILITY", "SLOT_CONTENTION"})),
                check("Successful bookings use different bays and different technicians",
                      len({x["bay"] for x in ok}) == len(ok) == len({x["tech"] for x in ok})),
                check("Ideal: exactly 2 booked and 1 NO_AVAILABILITY", len(ok) == 2 and failed_with(r, {"NO_AVAILABILITY"}), "ideal")]))

        r = self.race([(m, self.booking(self.saigon, m, "BRAKE_SERVICE", starts["saigon-brake"]), key(f"2-{m}")) for m in three])
        ok = [x for x in r if x["status"] == 201]
        sections.append(section(
            "2 · Technician bottleneck: 3 vehicles race for a brake service at 14:00, Saigon",
            "Both bays are free, but only Nguyen Van An is qualified for brakes, so at most 1 can be booked.", r, [
                check("Never more than 1 booking", len(ok) <= 1),
                check("A successful booking always goes to Nguyen Van An", all(x["tech"] == "Nguyen Van An" for x in ok)),
                check("Every failure is a 409 and persisted nothing", failed_with(r, {"NO_AVAILABILITY", "SLOT_CONTENTION"})),
                check("Ideal: 1 booked and 2 NO_AVAILABILITY", len(ok) == 1 and failed_with(r, {"NO_AVAILABILITY"}), "ideal")]))

        r = self.race([(f"Golf #{i + 1}", self.booking(self.london, "Golf", "OIL_CHANGE", starts["london-oil-0900"]), key(f"3-{i}"))
                       for i in range(6)])
        ok = [x for x in r if x["status"] == 201]
        sections.append(section(
            "3 · One vehicle: 6 requests with different keys book the Golf at 09:00, London",
            "London has bays and technicians to spare, but a vehicle cannot hold two overlapping appointments.", r, [
                check("The vehicle never gets more than 1 booking", len(ok) <= 1),
                check("Every failure is a 409 VEHICLE_ALREADY_BOOKED or SLOT_CONTENTION",
                      failed_with(r, {"VEHICLE_ALREADY_BOOKED", "SLOT_CONTENTION"})),
                check("Ideal: 1 booked and 5 VEHICLE_ALREADY_BOOKED",
                      len(ok) == 1 and failed_with(r, {"VEHICLE_ALREADY_BOOKED"}), "ideal")]))

        key4, body4 = key("4-corolla"), self.booking(self.london, "Corolla", "OIL_CHANGE", starts["london-oil-1200"])
        r = self.race([(f"Corolla #{i + 1}", body4, key4) for i in range(8)])
        ids = {x["id"] for x in r if x["status"] == 201}
        created = [a for a in self.schedule(self.london)
                   if a["status"] == "CONFIRMED" and a["startTime"] == starts["london-oil-1200"]]
        original = next(iter(ids), None)
        sections.append(section(
            "4 · Idempotency under concurrency: 8 requests with the SAME key",
            "The same Idempotency-Key and body must create exactly one appointment, and every 201 returns it.", r, [
                check(f"The database holds exactly 1 appointment for this key (found {len(created)})", len(created) == 1),
                check("Every 201 carries the same appointment id", len(ids) == 1),
                check("At most one response created it; every other 201 has Idempotent-Replayed: true",
                      sum(x["status"] == 201 and not x["replayed"] for x in r) <= 1),
                check("The only possible failure is 409 SLOT_CONTENTION, which persisted nothing", failed_with(r, {"SLOT_CONTENTION"})),
                check("Ideal: all 8 are 201, 1 created and 7 replayed",
                      all(x["status"] == 201 for x in r) and sum(x["replayed"] for x in r) == 7, "ideal")]))

        start4 = parse(starts["london-oil-1200"])
        later = (start4 + timedelta(minutes=30)).isoformat().replace("+00:00", "Z")
        same_instant = start4.astimezone(SAIGON_OFFSET).isoformat()
        focus = self.booking(self.london, "Focus", "OIL_CHANGE", starts["london-oil-1500"])
        steps = [
            ("Same key, 30 minutes later", self.book({**body4, "startTime": later}, key4), key4),
            (f"Same key, same instant as {same_instant[11:16]}+07:00", self.book({**body4, "startTime": same_instant}, key4), key4),
            ("Cancel that appointment", self.api.call("POST", f"/api/v1/appointments/{original}/cancel"), "-"),
            ("Original request again, after cancelling", self.book(body4, key4), key4),
            ("No key, first request (Focus 15:00)", self.book(focus), "(none)"),
            ("No key, identical second request", self.book(focus), "(none)"),
        ]
        s = [summarize(label, k, reply) for label, reply, k in steps]
        sections.append(section(
            "5 · Idempotency edge cases, one request after another",
            "Each step is sent after the previous one has answered.", s, [
                check("Same key, different start: 422 IDEMPOTENCY_KEY_REUSED", s[0]["code"] == "IDEMPOTENCY_KEY_REUSED"),
                check("Same instant in another offset: replays the original", s[1]["status"] == 201 and s[1]["replayed"] and s[1]["id"] == original),
                check("Cancel: 200 CANCELLED", s[2]["status"] == 200 and s[2]["apptStatus"] == "CANCELLED"),
                check("Replay after cancelling: 201, the original appointment, CANCELLED, nothing new",
                      s[3]["status"] == 201 and s[3]["id"] == original and s[3]["apptStatus"] == "CANCELLED" and s[3]["replayed"]),
                check("Without a key there is no deduplication: 201, then 409 VEHICLE_ALREADY_BOOKED",
                      s[4]["status"] == 201 and s[5]["code"] == "VEHICLE_ALREADY_BOOKED")], sequential=True))

        after = self.metrics()
        schedule = {"Saigon Auto Centre": self.schedule(self.saigon), "Riverside Motors London": self.schedule(self.london)}
        confirmed = [a for items in schedule.values() for a in items if a["status"] == "CONFIRMED"]
        integrity = [
            check(f"No two overlapping appointments on one bay ({len(confirmed)} confirmed that day)",
                  overlaps(confirmed, lambda a: a["serviceBay"]["id"]) == 0),
            check("No two overlapping appointments for one technician", overlaps(confirmed, lambda a: a["technician"]["id"]) == 0),
            check("No two overlapping appointments for one vehicle", overlaps(confirmed, lambda a: a["vehicleId"]) == 0)]
        models = {vehicle: model for model, (_, vehicle) in self.vehicles.items()}
        return {"run": self.run_id, "sections": sections, "integrity": integrity, "before": before, "after": after,
                "schedule": schedule, "models": models}


def summarize(label, key, reply):
    body = reply["body"] if isinstance(reply["body"], dict) else {}
    return {"label": label, "key": key, "status": reply["status"], "code": body.get("code"), "id": body.get("id"),
            "apptStatus": body.get("status"), "replayed": reply["headers"].get("Idempotent-Replayed") == "true",
            "requestId": reply["headers"].get("X-Request-Id"), "bay": (body.get("serviceBay") or {}).get("name"),
            "tech": (body.get("technician") or {}).get("name"), "sent": reply["sent"], "received": reply["received"]}


def section(title, why, results, checks, sequential=False):
    return {"title": title, "why": why, "results": results, "checks": checks, "sequential": sequential}


def check(text, ok, level="invariant"):
    return {"text": text, "ok": bool(ok), "level": level}


def failed_with(results, codes):
    return all(x["status"] == 409 and x["code"] in codes for x in results if x["status"] != 201)


def parse(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def overlaps(appointments, key):
    pairs = 0
    for i, a in enumerate(appointments):
        for b in appointments[i + 1:]:
            if key(a) == key(b) and parse(a["startTime"]) < parse(b["endTime"]) and parse(b["startTime"]) < parse(a["endTime"]):
                pairs += 1
    return pairs


def outcome(x):
    if x["status"] == 201 and x["replayed"]:
        return "replayed", "201 replay"
    if x["status"] in (200, 201):
        return "ok", f"{x['status']} {x['apptStatus'] or ''}".strip()
    return ("conflict" if x["status"] == 409 else "rejected"), f"{x['status']} {x['code']}"


def all_checks(report):
    return [c for s in report["sections"] for c in s["checks"]] + report["integrity"]


def print_summary(report):
    checks = all_checks(report)
    invariants = [c for c in checks if c["level"] == "invariant"]
    ideals = [c for c in checks if c["level"] == "ideal"]
    contention = sum(x["code"] == "SLOT_CONTENTION" for s in report["sections"] for x in s["results"])
    print(f"invariants {sum(c['ok'] for c in invariants)}/{len(invariants)}, "
          f"ideal outcomes {sum(c['ok'] for c in ideals)}/{len(ideals)}, SLOT_CONTENTION x{contention}")
    def mark(c):
        return "[ok]  " if c["ok"] else ("[FAIL]" if c["level"] == "invariant" else "[miss]")

    for s in report["sections"]:
        print(f"\n{s['title'].replace(' · ', '. ')}")
        for x in s["results"]:
            print(f"  {x['label']:<42.42} {outcome(x)[1]:<30} {(x['received'] - x['sent']) * 1000:6.0f} ms  {x['bay'] or ''} {x['tech'] or ''}")
        for c in s["checks"]:
            print(f"  {mark(c)} {c['text']}")
    print()
    for c in report["integrity"]:
        print(f"{mark(c)} {c['text']}")


def render(report, day, lock_timeout):
    checks = all_checks(report)
    invariants = [c for c in checks if c["level"] == "invariant"]
    ideals = [c for c in checks if c["level"] == "ideal"]
    passed, ideal_passed = sum(c["ok"] for c in invariants), sum(c["ok"] for c in ideals)
    contention = sum(x["code"] == "SLOT_CONTENTION" for s in report["sections"] for x in s["results"])

    def items(checks):
        return "".join(
            f'<li class="{("pass" if c["ok"] else "fail") if c["level"] == "invariant" else ("pass" if c["ok"] else "warn")}">'
            f'{"✓" if c["ok"] else ("✗" if c["level"] == "invariant" else "–")} {html.escape(c["text"])}</li>' for c in checks)

    def timeline(s):
        results = s["results"]
        start = min(x["sent"] for x in results)
        span = max(x["received"] for x in results) - start or 1
        rows = []
        for x in results:
            kind, text = outcome(x)
            left, width = (x["sent"] - start) / span * 100, max((x["received"] - x["sent"]) / span * 100, 0.6)
            detail = " · ".join(v for v in (x["bay"], x["tech"], (x["id"] or "")[:8]) if v)
            request = f" · req {html.escape(x['requestId'][:8])}" if x["requestId"] else ""
            rows.append(f'<div class="row"><div class="label">{html.escape(x["label"])}</div>'
                        f'<div class="track"><div class="bar {kind}" style="left:{left:.2f}%;width:{width:.2f}%"></div></div>'
                        f'<div class="result"><span class="pill {kind}">{html.escape(text)}</span> '
                        f'<span class="muted">{(x["received"] - x["sent"]) * 1000:.0f} ms</span>'
                        f'<div class="muted small">{html.escape(detail)}{request}</div></div></div>')
        note = "" if s["sequential"] else " (all sent together)"
        return "".join(rows) + f'<div class="axis"><span>0 ms{note}</span><span>{span * 1000:.0f} ms</span></div>'

    metric_rows = "".join(
        f"<tr><td>{html.escape(k)}</td><td>{report['before'].get(k, 0):.0f}</td><td>{report['after'].get(k, 0):.0f}</td>"
        f"<td><b>+{report['after'].get(k, 0) - report['before'].get(k, 0):.0f}</b></td></tr>"
        for k in sorted(set(report["before"]) | set(report["after"])))
    day_rows = "".join(
        f"<tr><td>{html.escape(name)}</td><td>{html.escape(a['serviceBay']['name'])}</td>"
        f"<td>{a['startTime'][11:16]}–{a['endTime'][11:16]}</td><td>{html.escape(str(report['models'].get(a['vehicleId'], a['vehicleId'])))}</td>"
        f"<td>{html.escape(a['technician']['name'])}</td></tr>"
        for name, appointments in report["schedule"].items()
        for a in sorted(appointments, key=lambda a: (a["serviceBay"]["name"], a["startTime"])) if a["status"] == "CONFIRMED")
    sections = "".join(
        f'<section><h2>{html.escape(s["title"])}</h2><p class="muted">{html.escape(s["why"])}</p>'
        f'<ul>{items(s["checks"])}</ul>{timeline(s)}</section>' for s in report["sections"])
    score_colour = "var(--pass)" if passed == len(invariants) else "var(--conflict)"
    return f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Race Demo Report</title><style>
:root{{--bg:#f4f6fa;--card:#fff;--ink:#0b1b2e;--muted:#4f5f73;--line:#d9e0ea;--ok:#1d6fb8;--replay:#7a93b8;--conflict:#c62828;--rejected:#8a5a00;--pass:#1b7a3d}}
@media (prefers-color-scheme:dark){{:root{{--bg:#0d1520;--card:#152233;--ink:#e8eef6;--muted:#a3b2c4;--line:#2a3b52;--ok:#5aa9f0;--replay:#8ea6c9;--conflict:#ff6b6b;--rejected:#e0a64a;--pass:#5fd08a}}}}
body{{margin:0;background:var(--bg);color:var(--ink);font:15px/1.5 system-ui,Segoe UI,sans-serif}}
main{{max-width:1080px;margin:0 auto;padding:24px 16px 48px}} h1{{margin:0 0 4px;font-size:24px}} h2{{font-size:17px;margin:0 0 4px}}
section{{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:16px 18px;margin:0 0 16px}}
.muted{{color:var(--muted)}} .small{{font-size:12px}} ul{{list-style:none;padding:0;margin:10px 0 14px}} li{{margin:2px 0}}
.pass{{color:var(--pass)}} .fail{{color:var(--conflict);font-weight:600}} .warn{{color:var(--rejected)}}
.row{{display:grid;grid-template-columns:150px 1fr 300px;gap:10px;align-items:center;padding:5px 0;border-top:1px solid var(--line)}}
.label{{font-weight:600;font-size:14px}} .track{{position:relative;height:16px;background:var(--bg);border-radius:4px}}
.bar{{position:absolute;top:0;bottom:0;border-radius:4px}} .bar.ok{{background:var(--ok)}} .bar.replayed{{background:var(--replay)}}
.bar.conflict{{background:var(--conflict)}} .bar.rejected{{background:var(--rejected)}}
.pill{{font-size:12px;font-weight:600;padding:1px 7px;border-radius:999px;border:1px solid currentColor}}
.pill.ok{{color:var(--ok)}} .pill.replayed{{color:var(--replay)}} .pill.conflict{{color:var(--conflict)}} .pill.rejected{{color:var(--rejected)}}
.axis{{display:flex;justify-content:space-between;color:var(--muted);font-size:12px;margin:0 310px 0 160px}}
.score{{font-size:20px;font-weight:700;color:{score_colour}}} .score2{{font-size:16px;font-weight:600;color:var(--muted)}}
table{{border-collapse:collapse;width:100%;font-size:14px}} td,th{{text-align:left;padding:4px 8px;border-top:1px solid var(--line)}} th{{color:var(--muted)}}
.legend span{{margin-right:14px;font-size:13px}} .dot{{display:inline-block;width:10px;height:10px;border-radius:2px;margin-right:4px;vertical-align:middle}}
@media (max-width:760px){{.row{{grid-template-columns:90px 1fr}} .result{{grid-column:1/-1}} .axis{{margin:0}}}}
</style></head><body><main>
<h1>Slot contention and idempotency, live</h1>
<p class="muted">Backend {html.escape(os.environ.get("SCHEDULER_API_URL", "http://localhost:8080"))} · day {day} · <b>booking-lock-timeout {html.escape(lock_timeout)}</b> · run {report["run"]} · each bar is one request, from sending it to its response</p>
<section><div class="score">Invariants (must always hold): {passed}/{len(invariants)}</div>
<div class="score2">Ideal outcomes (when no request times out waiting for the lock): {ideal_passed}/{len(ideals)} · {contention} × 409 SLOT_CONTENTION</div>
<p class="legend"><span><i class="dot" style="background:var(--ok)"></i>created</span><span><i class="dot" style="background:var(--replay)"></i>idempotent replay</span>
<span><i class="dot" style="background:var(--conflict)"></i>409 conflict</span><span><i class="dot" style="background:var(--rejected)"></i>422 rejected</span></p>
<p class="muted">The requests of a race arrive together. <code>pg_advisory_xact_lock</code> on the dealership and local day queues them, which is why the bars end one after another. A request that still slipped past the lock would be stopped by the <code>EXCLUDE USING gist</code> constraints.</p>
<p class="muted"><b>SLOT_CONTENTION</b> means a request waited longer than the lock timeout. It fails fast, persists nothing, and the client should retry (the web client says "Many bookings are being made for this day right now…"). It appears when one booking transaction takes long, for example when the backend is far from its database.</p></section>
{sections}
<section><h2>6 · The day read back</h2><p class="muted">Every confirmed appointment on {day}, checked pair by pair.</p><ul>{items(report["integrity"])}</ul>
<table><tr><th>Dealership</th><th>Bay</th><th>Local time</th><th>Vehicle</th><th>Technician</th></tr>{day_rows}</table></section>
<section><h2>7 · Prometheus counters</h2><p class="muted"><code>scheduler_bookings_total</code> by outcome and <code>scheduler_booking_conflicts_total</code>, before and after the run.</p>
<table><tr><th>outcome</th><th>before</th><th>after</th><th>change</th></tr>{metric_rows}</table></section>
</main></body></html>"""


def read_dotenv(path: Path) -> dict:
    values = {}
    if path.is_file():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def setting(name: str, dotenv: dict) -> str:
    return os.environ.get(name) or dotenv.get(name, "")


def fail(message: str) -> int:
    print(message, file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
