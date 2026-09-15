#!/usr/bin/env python3
"""Test data for the phoneDigits lookup (ECOSCRM-106) on a local ECOS stand.

Creates leads, deals and counterparties whose phone numbers reproduce the
production shape plus every branch the lookup has to survive, then checks that
searching by each expected key returns exactly the records that were built to
carry it.

Modes:
    generate  create the records and write the expectations file
    verify    query every expected key and diff against the expectations file
    cleanup   delete everything this script created

Every generated record carries MARKER in its description, so cleanup and
re-runs never touch records a human made.

Numbers are synthetic: the subscriber part always starts with 0, which the
Russian numbering plan does not assign, so no generated number can reach a
real subscriber. The formats themselves are the ECOSCRM-106 table verbatim -
they are the contract the normalization script is tested against.
"""

import argparse
import base64
import json
import random
import re
import sys
import urllib.error
import urllib.request
from collections import defaultdict

MARKER = "ECOSCRM-106-TESTDATA"
CRM_WORKSPACE = "crm-workspace"
MANAGER = "emodel/person@admin"

DEFAULT_URL = "http://localhost"
DEFAULT_USER = "admin"
DEFAULT_PASSWORD = "admin"

# ---------------------------------------------------------------------------
# the normalization algorithm, kept in step with opportunity.yml / ecos-counterparty.yml
# ---------------------------------------------------------------------------

SPLIT_RE = re.compile(r"[,;#/\r\n]|доб|доп|вн\.?|ext", re.IGNORECASE)


def phone_keys(*raw_values):
    """Expected keys for the given phone strings - the Python twin of the YAML script.

    This is a test oracle, not a third copy of the contract: if it drifts from the
    YAML the verify step fails loudly, which is the point.
    """
    keys = []
    for raw in raw_values:
        if raw is None:
            continue
        for part in SPLIT_RE.split(str(raw)):
            digits = re.sub(r"[^0-9]", "", part)
            # E.164 bounds: under ten digits is not a number, over fifteen is past the
            # standard maximum (that upper bound is also what discards two numbers glued
            # together by a space)
            if len(digits) < 10 or len(digits) > 15:
                continue
            # the key is the number in E.164, country code included - no country table is
            # needed, only the Russian trunk prefix is unfolded and a bare ten-digit number
            # defaults to +7
            key = digits
            if len(digits) == 10:
                key = "7" + digits
            elif len(digits) == 11 and digits[0] == "8":
                key = "7" + digits[1:]
            if key not in keys:
                keys.append(key)
    return keys


# ---------------------------------------------------------------------------
# phone formats - the ECOSCRM-106 table, with the subscriber part templated
# ---------------------------------------------------------------------------

# {s} is a 7-digit synthetic subscriber number starting with 0.
FORMATS_WITH_KEY = [
    "8495{s}",
    "+7495{s}",
    "+7 (495) {s0}-{s1}-{s2}",
    "+7(495){s} ",
    "8-495-{s0}-{s1}-{s2}, доб. 422",
    "+375 (44) {s0} {s1} {s2}",
    "+7-495-{s0}{s1}-{s2}",
    "+7 495 {s0}-{s1}-{s2}",
    "  +7 495 {s0}-{s1}-{s2}  ",
    "8 (495) {s0} {s1} {s2}",
]

# values that must produce no key at all
FORMATS_WITHOUT_KEY = ["630-20-10", "1", "8", "US", "[your-phone]", "нет телефона", "+", "-", ""]


def synthetic_number(rnd):
    """A 7-digit subscriber number that starts with 0, so it is never a real line."""
    return "0" + "".join(str(rnd.randint(0, 9)) for _ in range(6))


def render(fmt, subscriber):
    return (
        fmt.replace("{s0}", subscriber[0:3])
        .replace("{s1}", subscriber[3:5])
        .replace("{s2}", subscriber[5:7])
        .replace("{s}", subscriber)
    )


# ---------------------------------------------------------------------------
# Records API
# ---------------------------------------------------------------------------


class Api:
    def __init__(self, base_url, user, password, dry_run=False):
        self.base_url = base_url.rstrip("/")
        token = base64.b64encode(f"{user}:{password}".encode()).decode()
        self.auth = f"Basic {token}"
        self.dry_run = dry_run

    def _post(self, path, payload):
        req = urllib.request.Request(
            f"{self.base_url}{path}",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json", "Authorization": self.auth},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as err:
            body = err.read().decode("utf-8", "replace")[:800]
            raise SystemExit(f"HTTP {err.code} on {path}\n{body}")

    def mutate(self, records):
        if self.dry_run:
            return [{"id": f"dry-run@{i}"} for i in range(len(records))]
        res = self._post("/gateway/api/records/mutate", {"records": records})
        return res.get("records", [])

    def query(self, source_id, predicate, attributes=None, max_items=200):
        payload = {
            "query": {
                "sourceId": source_id,
                "query": predicate,
                "language": "predicate",
                "page": {"maxItems": max_items},
            },
            "attributes": attributes or {"id": "?localId"},
        }
        return self._post("/gateway/api/records/query", payload)

    def delete(self, record_ids):
        if self.dry_run or not record_ids:
            return
        self._post("/gateway/api/records/delete", {"records": record_ids})


# ---------------------------------------------------------------------------
# record builders
# ---------------------------------------------------------------------------


def contacts(*phones):
    return [
        {"contactFio": f"Контакт {i + 1}", "contactPhone": phone}
        for i, phone in enumerate(phones)
    ]


def deal_record(name, *, phone=None, contact_phones=(), counterparty=None, note=""):
    atts = {
        "_type": "emodel/type@deal",
        "_workspace": CRM_WORKSPACE,
        # without an explicit manager the computed role resolves to GROUP_crm-manager
        # and the record becomes invisible even to the administrator that created it
        "manager": MANAGER,
        "name": name,
        "description": f"{MARKER} {note}".strip(),
    }
    if phone is not None:
        atts["phone"] = phone
    if contact_phones:
        atts["contacts"] = contacts(*contact_phones)
    if counterparty:
        atts["counterparty"] = counterparty
    return {"id": "emodel/deal@", "attributes": atts}


def lead_record(name, *, contact_phones=(), counterparty=None, note=""):
    # lead does not declare `phone` - contacts are its only phone source
    atts = {
        "_type": "emodel/type@lead",
        "_workspace": CRM_WORKSPACE,
        "manager": MANAGER,
        "name": name,
        "description": f"{MARKER} {note}".strip(),
    }
    if contact_phones:
        atts["contacts"] = contacts(*contact_phones)
    if counterparty:
        atts["counterparty"] = counterparty
    return {"id": "emodel/lead@", "attributes": atts}


def counterparty_record(name, *, phone=None, cell_phone=None, contact_phones=(), note=""):
    # the counterparty type has no `name`/`description`: its display name is
    # fullOrganizationName, which is where the marker has to live
    atts = {
        "_type": "emodel/type@ecos-counterparty",
        "counterpartyType": "buyer",
        "counterpartyKind": "legalEntity",
        "shortOrganizationName": name,
        "fullOrganizationName": f"{name} [{MARKER}] {note}".strip(),
    }
    if phone is not None:
        atts["phone"] = phone
    if cell_phone is not None:
        atts["cellPhone"] = cell_phone
    if contact_phones:
        atts["contacts"] = contacts(*contact_phones)
    return {"id": "emodel/ecos-counterparty@", "attributes": atts}


# ---------------------------------------------------------------------------
# the scenario block - one record per named branch, with expected keys
# ---------------------------------------------------------------------------


def build_scenarios(rnd):
    """Returns (records, expectations) where expectations maps a scenario name to
    the keys its record must end up carrying."""
    scenarios = []

    def sc(name, record, expected_keys, lookup=True):
        scenarios.append(
            {"name": name, "record": record, "expected": expected_keys, "lookup": lookup}
        )

    # --- every format of the ECOSCRM-106 table, on a deal and on a counterparty ---
    for idx, fmt in enumerate(FORMATS_WITH_KEY):
        number = render(fmt, synthetic_number(rnd))
        sc(
            f"format-{idx}-deal",
            deal_record(f"Формат {idx} (сделка)", contact_phones=[number], note=f"format {idx}"),
            phone_keys(number),
        )
        sc(
            f"format-{idx}-counterparty",
            counterparty_record(
                f"ООО «Формат {idx}»", phone=number, note=f"format {idx}"
            ),
            phone_keys(number),
        )

    # --- values that must yield an empty array, never null ---
    for idx, bad in enumerate(FORMATS_WITHOUT_KEY):
        sc(
            f"nokey-{idx}-deal",
            deal_record(f"Без ключа {idx}", contact_phones=[bad], note=f"no key {idx}"),
            [],
            lookup=False,
        )
        sc(
            f"nokey-{idx}-counterparty",
            counterparty_record(f"ООО «Без ключа {idx}»", phone=bad, note=f"no key {idx}"),
            [],
            lookup=False,
        )

    # --- source combinations on a deal ---
    only_phone = render("+7 (495) {s0}-{s1}-{s2}", synthetic_number(rnd))
    only_contacts = render("+7 (495) {s0}-{s1}-{s2}", synthetic_number(rnd))
    both_a = render("8495{s}", synthetic_number(rnd))
    both_b = render("+7 495 {s0}-{s1}-{s2}", synthetic_number(rnd))

    sc(
        # `phone` is #Deprecated in deal.yml and is NOT a source of phoneDigits: on production it
        # always repeats the number already present in contacts, so reading it would add nothing
        # while tying the attribute to a field that is meant to disappear. A deal whose number
        # lives only there therefore gets no key at all - that is the expectation below.
        "deal-phone-only",
        deal_record("Сделка: только phone", phone=only_phone, note="phone only, no key expected"),
        [],
    )
    sc(
        "deal-contacts-only",
        deal_record("Сделка: только contacts", contact_phones=[only_contacts], note="contacts only"),
        phone_keys(only_contacts),
    )
    sc(
        "deal-both-sources-divergent",
        deal_record(
            "Сделка: phone и contacts расходятся",
            phone=both_a,
            contact_phones=[both_b],
            note="phone ignored, only the contact gives a key",
        ),
        phone_keys(both_b),
    )
    sc(
        "deal-both-sources-mirrored",
        deal_record(
            "Сделка: phone продублирован в contacts",
            phone=both_a,
            contact_phones=[both_a],
            note="the production shape: phone mirrored into contacts",
        ),
        phone_keys(both_a),
    )
    sc(
        "deal-no-phone-at-all",
        deal_record("Сделка без телефонов", note="no phone source"),
        [],
        lookup=False,
    )

    # --- cardinality ---
    many = [render("+7 (495) {s0}-{s1}-{s2}", synthetic_number(rnd)) for _ in range(5)]
    sc(
        "deal-five-contacts",
        deal_record("Сделка с пятью контактами", contact_phones=many, note="5 contacts"),
        phone_keys(*many),
    )
    multi_in_one = "{} / {}".format(
        render("+7 916 {s0}-{s1}-{s2}", synthetic_number(rnd)),
        render("+7 495 {s0}-{s1}-{s2}", synthetic_number(rnd)),
    )
    sc(
        "deal-two-numbers-in-one-string",
        deal_record(
            "Сделка: два номера в одной строке",
            contact_phones=[multi_in_one],
            note="two numbers in one contact",
        ),
        phone_keys(multi_in_one),
    )

    # --- every extension separator the script recognises ---
    base = render("8-495-{s0}-{s1}-{s2}", synthetic_number(rnd))
    for idx, ext in enumerate(
        ["доб. 422", "доп. 422", "вн. 422", "вн 422", "внутр. 422", "ext 422", "ДОБ. 422"]
    ):
        value = f"{base}, {ext}"
        sc(
            f"extension-{idx}",
            deal_record(f"Добавочный {idx}", contact_phones=[value], note=f"extension {ext}"),
            phone_keys(value),
        )
    for idx, sep in enumerate([";", "#", "/"]):
        value = f"{base} {sep} 422"
        sc(
            f"separator-{idx}",
            deal_record(f"Разделитель {idx}", contact_phones=[value], note=f"separator {sep}"),
            phone_keys(value),
        )

    # --- counterparty source combinations ---
    cp_phone = render("+7 (495) {s0}-{s1}-{s2}", synthetic_number(rnd))
    cp_cell = render("+7 916 {s0}-{s1}-{s2}", synthetic_number(rnd))
    cp_contact = render("+7 812 {s0}-{s1}-{s2}", synthetic_number(rnd))

    sc(
        "counterparty-phone-only",
        counterparty_record("ООО «Только phone»", phone=cp_phone, note="phone only"),
        phone_keys(cp_phone),
    )
    sc(
        "counterparty-cellphone-only",
        counterparty_record("ООО «Только cellPhone»", cell_phone=cp_cell, note="cellPhone only"),
        phone_keys(cp_cell),
    )
    sc(
        "counterparty-contacts-only",
        counterparty_record(
            "ООО «Только contacts»", contact_phones=[cp_contact], note="contacts only"
        ),
        phone_keys(cp_contact),
    )
    sc(
        "counterparty-all-three",
        counterparty_record(
            "ООО «Все три источника»",
            phone=cp_phone,
            cell_phone=cp_cell,
            contact_phones=[cp_contact],
            note="all three sources merge",
        ),
        phone_keys(cp_phone, cp_cell, cp_contact),
    )
    overlap = render("+7 (495) {s0}-{s1}-{s2}", synthetic_number(rnd))
    sc(
        "counterparty-overlapping-sources",
        counterparty_record(
            "ООО «Пересекающиеся источники»",
            phone=overlap,
            cell_phone=overlap.replace(" ", "").replace("(", "").replace(")", ""),
            contact_phones=[overlap],
            note="same number in all three sources - one key",
        ),
        phone_keys(overlap),
    )

    return scenarios


def build_collisions(rnd):
    """Records that deliberately share a key. This is the branch COREDEV-510 will
    hit in production and the one the stand has never exercised."""
    groups = []

    shared = render("+7 (495) {s0}-{s1}-{s2}", synthetic_number(rnd))
    groups.append(
        {
            "name": "key-on-three-deals",
            "key": phone_keys(shared)[0],
            "records": [
                deal_record(f"Коллизия: сделка {i + 1}", contact_phones=[shared], note="shared key")
                for i in range(3)
            ],
            "expect": {"emodel/deal": 3},
        }
    )

    cross = render("+7 916 {s0}-{s1}-{s2}", synthetic_number(rnd))
    groups.append(
        {
            "name": "key-on-deal-and-lead",
            "key": phone_keys(cross)[0],
            "records": [
                deal_record("Коллизия: сделка и лид", contact_phones=[cross], note="shared key"),
                lead_record("Коллизия: сделка и лид", contact_phones=[cross], note="shared key"),
            ],
            "expect": {"emodel/deal": 1, "emodel/lead": 1},
        }
    )

    return groups


def build_linked(api, rnd, batch_size):
    """The two-step lookup: find the counterparty by its own phone, then every
    opportunity that points at it. Needs two passes, because the deals have to
    carry the counterparty's ref.

    This is the branch COREDEV-510 actually runs and the one the stand has never
    had data for - the plan notes a counterparty is set on ~2% of deals, so it
    almost never fires on real records.
    """
    linked = []

    # (1) a counterparty with four deals and one lead hanging off it, none of which
    #     carry a phone of their own: they are reachable only through the counterparty
    fan_number = render("+7 (495) {s0}-{s1}-{s2}", synthetic_number(rnd))
    # (2) a counterparty whose number ALSO sits in its own deal's contacts: the
    #     combined or(...) query must return that deal once, not twice
    overlap_number = render("+7 (495) {s0}-{s1}-{s2}", synthetic_number(rnd))

    cp_records = [
        counterparty_record("Веерный контрагент", phone=fan_number, note="two-step fan-out"),
        counterparty_record("Контрагент с пересечением", phone=overlap_number, note="two-step overlap"),
    ]
    cp_ids = send_in_batches(api, cp_records, batch_size, "linked counterparties")
    fan_ref, overlap_ref = cp_ids[0], cp_ids[1]

    children = [
        deal_record(f"Сделка веера {i + 1}", counterparty=fan_ref, note="two-step fan-out")
        for i in range(4)
    ]
    children.append(
        lead_record("Лид веера", counterparty=fan_ref, note="two-step fan-out")
    )
    children.append(
        deal_record(
            "Сделка с пересечением",
            counterparty=overlap_ref,
            contact_phones=[overlap_number],
            note="two-step overlap",
        )
    )
    child_ids = send_in_batches(api, children, batch_size, "linked opportunities")

    linked.append(
        {
            "name": "two-step-fan-out",
            "counterparty": fan_ref,
            "key": phone_keys(fan_number)[0],
            "deals": child_ids[0:4],
            "leads": [child_ids[4]],
            # the children have no phone of their own - the key must NOT match them directly
            "children_carry_key": False,
        }
    )
    linked.append(
        {
            "name": "two-step-overlap",
            "counterparty": overlap_ref,
            "key": phone_keys(overlap_number)[0],
            "deals": [child_ids[5]],
            "leads": [],
            "children_carry_key": True,
        }
    )
    return linked


# ---------------------------------------------------------------------------
# bulk block - production-shaped volume so the planner sees a real table
# ---------------------------------------------------------------------------


def build_bulk(rnd, n_deals, n_leads, n_counterparties):
    """Distribution taken from the production figures recorded in the plan:
    28% of deals fill the deprecated `phone` (1213 of 4280), counterparty is set
    on ~2% of deals and ~6% of leads."""
    records = []

    for i in range(n_counterparties):
        number = render(rnd.choice(FORMATS_WITH_KEY), synthetic_number(rnd))
        kwargs = {}
        roll = rnd.random()
        if roll < 0.55:
            kwargs["phone"] = number
        elif roll < 0.80:
            kwargs["cell_phone"] = number
        elif roll < 0.95:
            kwargs["contact_phones"] = [number]
        else:
            pass  # 5% with no phone at all, as production has
        records.append(
            counterparty_record(f"ООО «Массив {i:05d}»", note="bulk", **kwargs)
        )

    for i in range(n_deals):
        kwargs = {}
        roll = rnd.random()
        if roll < 0.83:
            count = 1 if rnd.random() < 0.8 else rnd.randint(2, 4)
            kwargs["contact_phones"] = [
                render(rnd.choice(FORMATS_WITH_KEY), synthetic_number(rnd))
                for _ in range(count)
            ]
        if roll < 0.28:
            # the production shape, and the reason `phone` is not a source: every deal that fills
            # the deprecated field repeats the number already present in contacts. Filling it with
            # an independent number here would invent a case production does not have.
            kwargs["phone"] = kwargs["contact_phones"][0]
        records.append(deal_record(f"Сделка «Массив {i:05d}»", note="bulk", **kwargs))

    for i in range(n_leads):
        kwargs = {}
        if rnd.random() < 0.65:
            # a lead can carry several contacts just like a deal; generating exactly one left the
            # multi-contact branch untested on lead at volume
            count = 1 if rnd.random() < 0.8 else rnd.randint(2, 3)
            kwargs["contact_phones"] = [
                render(rnd.choice(FORMATS_WITH_KEY), synthetic_number(rnd))
                for _ in range(count)
            ]
        records.append(lead_record(f"Лид «Массив {i:05d}»", note="bulk", **kwargs))

    return records


# ---------------------------------------------------------------------------
# drivers
# ---------------------------------------------------------------------------


def send_in_batches(api, records, batch_size, label):
    created = []
    total = len(records)
    for start in range(0, total, batch_size):
        chunk = records[start : start + batch_size]
        res = api.mutate(chunk)
        created.extend(r.get("id") for r in res)
        done = min(start + batch_size, total)
        print(f"  {label}: {done}/{total}", file=sys.stderr)
    return created


def auto_start_check(api, args):
    """Refuse to create thousands of records if each one starts a business process.

    Learned the hard way on 2026-09-15: a full run created ~4200 deals, every one of
    which auto-started `deal-take-to-work-process` (the definition carries
    ecos:autoStartEnabled="true" and ecos:ecosType="emodel/type@deal"). The process
    service ran out of heap, stopped answering, and left ~11k messages queued.

    A handful of records does not show this - the first smoke test of three was clean -
    so the guard creates a small batch, looks at whether processes appeared for it, and
    aborts before the damage scales. Pass --allow-process-start to skip, but only when
    process start is genuinely wanted or already disabled on the stand.
    """
    # The types this generator creates records of. A process definition that auto-starts
    # on any of them will fire once per generated record.
    target_types = {"emodel/type@deal", "emodel/type@lead", "emodel/type@ecos-counterparty"}

    res = api._post(
        "/gateway/api/records/query",
        {
            "query": {
                "sourceId": "eproc/bpmn-def",
                "query": {"t": "not-empty", "att": "_created"},
                "language": "predicate",
                "page": {"maxItems": 1000},
            },
            "attributes": {
                "auto": "autoStartEnabled?bool",
                "enabled": "enabled?bool",
                "ecosType": "ecosType?str",
                "disp": "?disp",
            },
        },
    )

    errors = res.get("messages") or []
    if errors:
        # eproc unreachable: a down process service cannot start anything, but it also
        # cannot be asked, so say so plainly instead of silently treating it as a pass
        raise SystemExit(
            "aborted: cannot read process definitions from eproc, so the auto-start check "
            "could not run. Bring eproc up and retry, or pass --allow-process-start if you "
            "know no process will start."
        )

    offenders = [
        rec["attributes"]
        for rec in res.get("records", [])
        if rec["attributes"].get("auto")
        and rec["attributes"].get("enabled")
        and rec["attributes"].get("ecosType") in target_types
    ]

    if offenders:
        lines = "\n".join(
            f"  - {o.get('disp')} ({o.get('ecosType')})" for o in offenders
        )
        raise SystemExit(
            "aborted: these process definitions auto-start on the types this generator "
            f"creates:\n{lines}\n"
            "A full run would start one process per record - roughly one per generated "
            "deal and lead - and exhaust the process service. On 2026-09-15 that put "
            "eproc into a repeating OutOfMemoryError and left ~11k queued messages.\n"
            "Turn auto start off for them on the stand first (do NOT edit the .bpmn.xml in "
            "the repository - that is production behaviour), or pass --allow-process-start "
            "if you really mean it."
        )
    print("auto-start check: nothing starts on the target types, continuing", file=sys.stderr)


def cmd_generate(api, args):
    rnd = random.Random(args.seed)

    if not args.allow_process_start and not api.dry_run:
        auto_start_check(api, args)

    scenarios = build_scenarios(rnd)
    collisions = build_collisions(rnd)

    expectations = {"scenarios": [], "collisions": [], "counts": {}}

    print("scenario block...", file=sys.stderr)
    ids = send_in_batches(
        api, [s["record"] for s in scenarios], args.batch, "scenarios"
    )
    for scenario, record_id in zip(scenarios, ids):
        expectations["scenarios"].append(
            {
                "name": scenario["name"],
                "ref": record_id,
                "expected": scenario["expected"],
                "lookup": scenario["lookup"],
            }
        )

    print("collision block...", file=sys.stderr)
    for group in collisions:
        ids = send_in_batches(api, group["records"], args.batch, group["name"])
        expectations["collisions"].append(
            {"name": group["name"], "key": group["key"], "refs": ids, "expect": group["expect"]}
        )

    print("linked block...", file=sys.stderr)
    expectations["linked"] = build_linked(api, rnd, args.batch)

    print("bulk block...", file=sys.stderr)
    bulk = build_bulk(rnd, args.deals, args.leads, args.counterparties)
    send_in_batches(api, bulk, args.batch, "bulk")
    expectations["counts"] = {
        "scenarios": len(scenarios),
        "collisions": sum(len(g["records"]) for g in collisions),
        "linked": sum(1 + len(g["deals"]) + len(g["leads"]) for g in expectations["linked"]),
        "bulk": len(bulk),
    }

    with open(args.expectations, "w", encoding="utf-8") as fh:
        json.dump(expectations, fh, ensure_ascii=False, indent=2)
    print(f"expectations written to {args.expectations}", file=sys.stderr)


def cmd_verify(api, args):
    with open(args.expectations, encoding="utf-8") as fh:
        expectations = json.load(fh)

    failures = []
    checked = 0

    # 1. every scenario record carries exactly the expected keys
    refs = [s["ref"] for s in expectations["scenarios"]]
    by_ref = {}
    for start in range(0, len(refs), 100):
        chunk = refs[start : start + 100]
        res = api._post(
            "/gateway/api/records/query",
            {"records": chunk, "attributes": {"pd": "phoneDigits[]?str"}},
        )
        for rec in res.get("records", []):
            by_ref[rec["id"]] = rec["attributes"].get("pd") or []

    for scenario in expectations["scenarios"]:
        checked += 1
        actual = by_ref.get(scenario["ref"], None)
        if actual is None:
            failures.append(f"{scenario['name']}: record {scenario['ref']} not readable")
            continue
        if sorted(actual) != sorted(scenario["expected"]):
            failures.append(
                f"{scenario['name']}: expected {sorted(scenario['expected'])}, got {sorted(actual)}"
            )

    # 2. searching by each expected key finds the record that carries it
    for scenario in expectations["scenarios"]:
        if not scenario["lookup"]:
            continue
        source = scenario["ref"].split("@")[0]
        for key in scenario["expected"]:
            checked += 1
            res = api.query(source, {"t": "eq", "att": "phoneDigits", "val": key})
            found = [r["id"] for r in res.get("records", [])]
            if scenario["ref"] not in found:
                failures.append(
                    f"{scenario['name']}: lookup by {key} did not return {scenario['ref']} "
                    f"(returned {len(found)} records)"
                )

    # 3. collisions return every record that shares the key, not just the first
    for group in expectations["collisions"]:
        for source, expected_count in group["expect"].items():
            checked += 1
            res = api.query(source, {"t": "eq", "att": "phoneDigits", "val": group["key"]})
            found = [r["id"] for r in res.get("records", []) if r["id"] in group["refs"]]
            if len(found) != expected_count:
                failures.append(
                    f"{group['name']}: {source} by key {group['key']} returned "
                    f"{len(found)} of the expected {expected_count}"
                )

    # 4. the two-step lookup COREDEV-510 runs: counterparty by key, then the
    #    opportunities pointing at it
    for group in expectations.get("linked", []):
        checked += 1
        res = api.query(
            "emodel/ecos-counterparty", {"t": "eq", "att": "phoneDigits", "val": group["key"]}
        )
        cp_found = [r["id"] for r in res.get("records", [])]
        if group["counterparty"] not in cp_found:
            failures.append(
                f"{group['name']}: step 1 did not find counterparty {group['counterparty']} "
                f"by key {group['key']}"
            )
            continue

        for source, expected_refs in (
            ("emodel/deal", group["deals"]),
            ("emodel/lead", group["leads"]),
        ):
            if not expected_refs:
                continue
            checked += 1
            res = api.query(
                source, {"t": "in", "att": "counterparty", "val": [group["counterparty"]]}
            )
            found = [r["id"] for r in res.get("records", [])]
            missing = [ref for ref in expected_refs if ref not in found]
            if missing:
                failures.append(
                    f"{group['name']}: step 2 on {source} missed {len(missing)} of "
                    f"{len(expected_refs)} records"
                )

            # the combined form: it must return each record exactly once, even when
            # the key matches both the record itself and its counterparty
            checked += 1
            res = api.query(
                source,
                {
                    "t": "or",
                    "val": [
                        {"t": "eq", "att": "phoneDigits", "val": group["key"]},
                        {"t": "in", "att": "counterparty", "val": [group["counterparty"]]},
                    ],
                },
            )
            found = [r["id"] for r in res.get("records", [])]
            for ref in expected_refs:
                if found.count(ref) != 1:
                    failures.append(
                        f"{group['name']}: combined or(...) on {source} returned {ref} "
                        f"{found.count(ref)} times, expected exactly 1"
                    )

        # a record reachable only through its counterparty must NOT match the key itself
        if not group["children_carry_key"]:
            checked += 1
            res = api.query("emodel/deal", {"t": "eq", "att": "phoneDigits", "val": group["key"]})
            leaked = [r["id"] for r in res.get("records", []) if r["id"] in group["deals"]]
            if leaked:
                failures.append(
                    f"{group['name']}: {len(leaked)} deals carry the counterparty's key "
                    f"directly, so the two-step branch is not actually being exercised"
                )

    # 5. a key that belongs to nobody returns nothing, without an error
    for source in ("emodel/deal", "emodel/lead", "emodel/ecos-counterparty"):
        checked += 1
        res = api.query(source, {"t": "eq", "att": "phoneDigits", "val": "0000000000"})
        if res.get("totalCount", 0) != 0:
            failures.append(f"{source}: unknown key returned {res.get('totalCount')} records")

    print(f"checks run: {checked}")
    if failures:
        print(f"FAILURES: {len(failures)}")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print("all checks passed")
    return 0


# the attribute the marker lives in, per source: the counterparty type has no description
MARKER_ATT = {
    "emodel/deal": "description",
    "emodel/lead": "description",
    "emodel/ecos-counterparty": "fullOrganizationName",
}


def cmd_cleanup(api, args):
    total = 0
    for source, marker_att in MARKER_ATT.items():
        while True:
            res = api.query(
                source,
                {"t": "contains", "att": marker_att, "val": MARKER},
                max_items=200,
            )
            ids = [r["id"] for r in res.get("records", [])]
            if not ids:
                break
            api.delete(ids)
            total += len(ids)
            print(f"  {source}: deleted {len(ids)} (running total {total})", file=sys.stderr)
    print(f"deleted {total} records")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["generate", "verify", "cleanup"])
    parser.add_argument("--url", default=DEFAULT_URL)
    parser.add_argument("--user", default=DEFAULT_USER)
    parser.add_argument("--password", default=DEFAULT_PASSWORD)
    parser.add_argument("--deals", type=int, default=4146)
    parser.add_argument("--leads", type=int, default=923)
    parser.add_argument("--counterparties", type=int, default=474)
    parser.add_argument("--batch", type=int, default=25)
    parser.add_argument("--seed", type=int, default=106)
    parser.add_argument("--expectations", default="phone-digits-expectations.json")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument(
        "--allow-process-start",
        action="store_true",
        help="skip the canary check that refuses to run when creating a record auto-starts "
        "a business process",
    )
    args = parser.parse_args()

    api = Api(args.url, args.user, args.password, dry_run=args.dry_run)

    if args.mode == "generate":
        cmd_generate(api, args)
        return 0
    if args.mode == "verify":
        return cmd_verify(api, args)
    return cmd_cleanup(api, args)


if __name__ == "__main__":
    sys.exit(main())
