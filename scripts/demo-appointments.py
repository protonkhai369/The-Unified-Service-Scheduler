#!/usr/bin/env python3
"""Books sample appointments through the running backend, so every screen of the web client has data.

Signs in as the demo admin (create the demo users first with scripts/supabase-demo-users.py) and books
through POST /api/v1/appointments, so every booking passes the same rules, lock and constraints as a
real one. Dates are relative to today at the Saigon dealership:

    today        Saigon  Focus tyre rotation at the first free slot     the advisor's start page; once
                                                                         started, cancelling answers 409
    today + 4    Saigon  three full services back to back, 07:30-16:30  the only brake/full technician is
                                                                         busy all day: "no free time"
    today + 5    London  two oil changes at 09:00, tyre rotation 10:00,  two bays in use at once
                         brake service 14:00
    today + 6    Saigon  oil change 08:00, booked then cancelled         "Past or cancelled" for Emma

Usage:  DEMO_USER_PASSWORD='...' python scripts/demo-appointments.py
Reads:  SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY from the environment or from .env in the project root;
        SCHEDULER_API_URL (default http://localhost:8080); DEMO_USER_PASSWORD from the environment only.
Needs:  Python 3.9+, standard library only, and the backend running.

Every booking has a fixed Idempotency-Key per date, so re-running the same day replays instead of
booking twice. Neither the key nor the password is ever printed.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

SAIGON_OFFSET = timezone(timedelta(hours=7))


def main() -> int:
    settings = read_dotenv(Path(__file__).resolve().parent.parent / ".env")
    supabase_url = setting("SUPABASE_URL", settings).rstrip("/")
    publishable_key = setting("SUPABASE_PUBLISHABLE_KEY", settings)
    password = os.environ.get("DEMO_USER_PASSWORD", "")
    if not supabase_url or not publishable_key or not password:
        return fail("Set SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY (in .env or the environment) and DEMO_USER_PASSWORD.")

    api = Api(setting("SCHEDULER_API_URL", settings) or "http://localhost:8080")
    status, body = api.call("POST", f"{supabase_url}/auth/v1/token?grant_type=password", absolute=True,
                            body={"email": "scheduler.admin@example.com", "password": password},
                            headers={"apikey": publishable_key})
    if status != 200:
        return fail(f"Sign-in as the demo admin failed ({status}). Run scripts/supabase-demo-users.py first.")
    api.token = body["access_token"]

    dealerships = {d["name"]: d["id"] for d in api.call("GET", "/api/v1/dealerships")[1]["items"]}
    services = {s["code"]: s["id"] for s in api.call("GET", "/api/v1/service-types")[1]["items"]}
    vehicles = {}
    for query in ("emma.wilson", "minh.duc"):
        for customer in api.call("GET", f"/api/v1/customers?q={query}")[1]["items"]:
            for vehicle in customer["vehicles"]:
                vehicles[vehicle["model"]] = (customer["id"], vehicle["id"])
    missing = {"Golf", "Corolla", "Focus"} - vehicles.keys()
    if missing:
        return fail(f"Demo vehicles not found: {sorted(missing)}. Is the demo data loaded?")

    saigon, london = dealerships["Saigon Auto Centre"], dealerships["Riverside Motors London"]
    today = datetime.now(SAIGON_OFFSET).date()

    def slot(dealership: int, service: str, day: date, local_time: str | None):
        query = urllib.parse.urlencode({"serviceTypeId": services[service], "date": day.isoformat()})
        status, body = api.call("GET", f"/api/v1/dealerships/{dealership}/availability?{query}")
        slots = body.get("slots", []) if status == 200 else []
        if local_time is None:
            return slots[0]["startTime"] if slots else None
        return next((s["startTime"] for s in slots if s["startTime"][11:16] == local_time), None)

    plan = [
        (saigon, today, "Focus", "TYRE_ROTATION", None, False),
        (saigon, today + timedelta(days=4), "Golf", "FULL_SERVICE", "07:30", False),
        (saigon, today + timedelta(days=4), "Corolla", "FULL_SERVICE", "10:30", False),
        (saigon, today + timedelta(days=4), "Focus", "FULL_SERVICE", "13:30", False),
        (london, today + timedelta(days=5), "Golf", "OIL_CHANGE", "09:00", False),
        (london, today + timedelta(days=5), "Corolla", "OIL_CHANGE", "09:00", False),
        (london, today + timedelta(days=5), "Focus", "TYRE_ROTATION", "10:00", False),
        (london, today + timedelta(days=5), "Corolla", "BRAKE_SERVICE", "14:00", False),
        (saigon, today + timedelta(days=6), "Corolla", "OIL_CHANGE", "08:00", True),
    ]
    names = {v: k for k, v in dealerships.items()}
    for dealership, day, model, service, local_time, cancel in plan:
        key = f"demo-{day.isoformat()}-{model}-{service}-{local_time or 'first'}".lower().replace(":", "")
        start = slot(dealership, service, day, local_time)
        label = f"{day.isoformat()} {names[dealership]:<24} {local_time or 'first':<5} {model:<8} {service:<14}"
        if start is None:
            print(f"skip      {label} not free (already booked, or too late today)")
            continue
        customer_id, vehicle_id = vehicles[model]
        status, body = api.call("POST", "/api/v1/appointments", body={
            "dealershipId": dealership, "customerId": customer_id, "vehicleId": vehicle_id,
            "serviceTypeId": services[service], "startTime": start}, headers={"Idempotency-Key": key})
        if status != 201:
            print(f"{status:<9} {label} {body.get('code')}")
            continue
        outcome = "booked"
        if cancel:
            api.call("POST", f"/api/v1/appointments/{body['id']}/cancel")
            outcome = "cancelled"
        print(f"{outcome:<9} {label} {body['serviceBay']['name']} / {body['technician']['name']}")
    return 0


class Api:
    def __init__(self, base: str):
        self.base = base.rstrip("/")
        self.token = None

    def call(self, method: str, path: str, body=None, headers=None, absolute=False):
        request = urllib.request.Request(path if absolute else self.base + path, method=method,
                                         data=json.dumps(body).encode() if body is not None else None)
        request.add_header("Content-Type", "application/json")
        if self.token and not absolute:
            request.add_header("Authorization", f"Bearer {self.token}")
        for key, value in (headers or {}).items():
            request.add_header(key, value)
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.status, json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as error:
            try:
                return error.code, json.loads(error.read() or b"{}")
            except ValueError:
                return error.code, {}
        except urllib.error.URLError as error:
            sys.exit(f"Cannot reach {request.full_url}: {error.reason}. Is the backend running?")


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
