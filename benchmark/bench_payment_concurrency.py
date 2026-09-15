"""Local HTTP contention benchmark with independent MySQL invariant checks.

Use only with the isolated load database created for this run. No data is deleted.
The gateway is LabFlow's simulated channel, not a real payment provider.
"""
import argparse
import concurrent.futures
import json
import pathlib
import subprocess
import threading
import time
import uuid
from collections import Counter
from datetime import datetime, timedelta, timezone

from bench_reservation import http_json, summarize_latencies


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://127.0.0.1:28080')
    parser.add_argument('--output', required=True)
    parser.add_argument('--query-script', required=True)
    parser.add_argument('--secret-file', required=True)
    args = parser.parse_args()
    if args.base_url != 'http://127.0.0.1:28080':
        raise ValueError('This benchmark is restricted to the isolated local backend')
    out = pathlib.Path(args.output)
    out.mkdir(parents=True, exist_ok=True)
    secret = pathlib.Path(args.secret_file).read_text().strip().split('=', 1)[1]
    stamp = uuid.uuid4().hex[:10]
    summaries = []
    evidence = []
    violations = []

    def sql(query):
        p = subprocess.run(['wsl', '-d', 'Ubuntu', '-u', 'root', '--', 'sh', args.query_script],
                           input=query + ';\n', capture_output=True, encoding='utf-8', timeout=30)
        if p.returncode:
            raise RuntimeError(p.stderr)
        return [line.split('\t') for line in p.stdout.strip().splitlines()]

    def request(method, path, token=None, body=None, callback=False):
        headers = {'Authorization': 'Bearer ' + token} if token else {}
        if callback:
            headers['X-Channel-Token'] = secret
        status, data, ms, text = http_json(method, args.base_url + path, headers, body, timeout=30)
        return {'status': status, 'data': data, 'latency_ms': ms,
                'error': text[:300] if status == 0 or status >= 500 else None}

    def must(method, path, token=None, body=None, expected=200):
        r = request(method, path, token, body)
        if r['status'] != expected:
            raise RuntimeError(f'Setup failed {method} {path}: {r}')
        return r['data']

    def burst(name, n, round_no, method, path, token, body, allowed, callback=False):
        barrier = threading.Barrier(n)
        def worker(index):
            barrier.wait(timeout=30)
            return request(method, path(index) if callable(path) else path, token, body, callback)
        started = time.perf_counter()
        with concurrent.futures.ThreadPoolExecutor(max_workers=n) as pool:
            rows = list(pool.map(worker, range(n)))
        wall = time.perf_counter() - started
        bad = [r for r in rows if (r['status'], (r['data'] or {}).get('code')) not in allowed
               and (r['status'], None) not in allowed]
        summary = {'scenario': name, 'concurrency': n, 'round': round_no, 'requests': n,
                   'wall_seconds': wall, 'requests_per_second': n / wall,
                   'http_statuses': dict(Counter(str(r['status']) for r in rows)),
                   'business_codes': dict(Counter(str((r['data'] or {}).get('code')) for r in rows)),
                   'non_2xx_rate': sum(not 200 <= r['status'] < 300 for r in rows) / n,
                   'unexpected_failure_rate': len(bad) / n,
                   'latency': summarize_latencies([r['latency_ms'] for r in rows])}
        summaries.append(summary)
        with (out / 'responses.jsonl').open('a', encoding='utf-8') as f:
            for r in rows:
                f.write(json.dumps({'scenario': name, 'concurrency': n, 'round': round_no, **r}) + '\n')
        (out / 'summary.json').write_text(json.dumps(summaries, indent=2), encoding='utf-8')
        print(json.dumps(summary), flush=True)
        if bad:
            violations.append({'scenario': name, 'concurrency': n, 'round': round_no,
                               'unexpected_responses': bad})
            (out / 'violations.json').write_text(json.dumps(violations, indent=2), encoding='utf-8')
        return rows

    admin = must('POST', '/api/auth/login', body={'username': 'admin', 'password': 'admin123'})['accessToken']
    student = must('POST', '/api/auth/login', body={'username': 'student', 'password': 'student123'})['accessToken']
    for n in (10, 25, 50):
        for round_no in (1, 2, 3):
            def fixture(suffix):
                eq = must('POST', '/api/equipment', admin, {
                    'code': f'LOAD-{stamp}-{n}-{round_no}-{suffix}', 'name': 'Isolated load fixture',
                    'category': 'benchmark', 'location': 'local', 'hourlyPriceCents': 1000}, 201)
                start = datetime.now(timezone.utc).replace(microsecond=0) + timedelta(days=2)
                payload = {'equipmentId': eq['id'], 'purpose': 'Isolated concurrency benchmark',
                           'startTime': start.isoformat(), 'endTime': (start + timedelta(hours=1)).isoformat()}
                return eq['id'], payload

            eqid, payload = fixture('callback')
            rows = burst('reservation', n, round_no, 'POST', '/api/reservations', student, payload,
                         {(201, None), (409, 'RESERVATION_QUOTA_EXCEEDED'), (409, 'RESERVATION_LOCK_TIMEOUT')})
            wins = [r for r in rows if r['status'] == 201]
            assert 1 <= len(wins) <= 20, ('reservation quota invariant', wins)
            ids = [r['data']['id'] for r in wins]
            rows = burst('overlapping_approval', n, round_no, 'PATCH',
                         lambda i: f'/api/reservations/{ids[i % len(ids)]}/decision', admin,
                         {'decision': 'APPROVED'}, {(200, None), (409, 'RESERVATION_NOT_PENDING'),
                                                  (409, 'RESERVATION_CONFLICT')})
            approved_count = sum(r['status'] == 200 for r in rows)
            occupied = sql(f"SELECT id,status FROM equipment_reservations WHERE equipment_id={eqid} AND status IN ('APPROVED','AWAITING_PAYMENT','PAID')")
            if approved_count != 1 or len(occupied) != 1:
                violations.append({'scenario': 'overlapping_approval', 'concurrency': n, 'round': round_no,
                                   'success_responses': approved_count, 'occupied_rows_before_cleanup': occupied})
                (out / 'violations.json').write_text(json.dumps(violations, indent=2), encoding='utf-8')
            rid = next(r['data']['id'] for r in rows if r['status'] == 200)
            for pending_id in ids:
                if pending_id != rid:
                    must('PATCH', f'/api/reservations/{pending_id}/cancel', student)
            order = sql(f'SELECT order_no,amount_cents FROM payment_orders WHERE reservation_id={rid}')
            assert len(order) == 1
            order_no, amount = order[0]
            callback = {'orderNo': order_no, 'idempotencyKey': f'load-{stamp}-{rid}', 'type': 'PAYMENT',
                        'amountCents': int(amount), 'channelTxnId': f'load-channel-{stamp}-{rid}',
                        'status': 'SUCCESS', 'occurredAt': datetime.now(timezone.utc).isoformat()}
            rows = burst('callback', n, round_no, 'POST', '/api/payments/callback', None, callback,
                         {(200, None)}, callback=True)
            assert sum(r['data'].get('accepted', False) for r in rows) == 1, 'duplicate accepted callback'

            eq2, payload2 = fixture('pay')
            rid2 = must('POST', '/api/reservations', student, payload2, 201)['id']
            rows = burst('same_reservation_approval', n, round_no, 'PATCH',
                         f'/api/reservations/{rid2}/decision', admin, {'decision': 'APPROVED'},
                         {(200, None), (409, 'RESERVATION_NOT_PENDING')})
            assert sum(r['status'] == 200 for r in rows) == 1, 'duplicate same-reservation approval'
            order2 = sql(f'SELECT order_no FROM payment_orders WHERE reservation_id={rid2}')[0][0]
            burst('payment_initiation', n, round_no, 'POST', f'/api/payments/orders/{order2}/pay', student,
                  None, {(200, None), (409, 'PAYMENT_ORDER_NOT_PAYABLE')})
            # Dispatch is asynchronous: wait for the actual ledger, with a fixed deadline.
            deadline = time.monotonic() + 20
            while must('GET', f'/api/payments/orders/{order2}', student)['status'] != 'PAID':
                if time.monotonic() >= deadline:
                    raise AssertionError('payment dispatch did not settle')
                time.sleep(.2)

            for reservation_id, equipment_id in ((rid, eqid), (rid2, eq2)):
                counts = sql(f"SELECT (SELECT COUNT(*) FROM equipment_reservations WHERE equipment_id={equipment_id} AND status IN ('APPROVED','AWAITING_PAYMENT','PAID')),"
                             f"(SELECT COUNT(*) FROM operation_logs WHERE target_id={reservation_id} AND action='RESERVATION_APPROVED'),"
                             f"(SELECT COUNT(*) FROM operation_logs WHERE target_id={reservation_id} AND action='PAYMENT_SUCCEEDED'),"
                             f"(SELECT COUNT(*) FROM payment_transactions t JOIN payment_orders o ON t.order_no=o.order_no WHERE o.reservation_id={reservation_id} AND t.type='PAYMENT'),"
                             f"(SELECT COUNT(*) FROM payment_orders WHERE reservation_id={reservation_id} AND paid_cents=amount_cents AND refunded_cents=0 AND status='PAID')")
                assert counts == [['1', '1', '1', '1', '1']], ('database invariant failure', reservation_id, counts)
                evidence.append({'reservation_id': reservation_id, 'equipment_id': equipment_id,
                                 'one_occupied_reservation_approval_payment_audit_transaction_paid_order': counts[0]})
            intents = sql(f"SELECT COUNT(*) FROM payment_requests WHERE order_no='{order2}' AND type='PAYMENT'")
            assert intents == [['1']], ('duplicate outbound intent', intents)
            (out / 'database-evidence.json').write_text(json.dumps(evidence, indent=2), encoding='utf-8')
    (out / 'violations.json').write_text(json.dumps(violations, indent=2), encoding='utf-8')
    print('VIOLATIONS_FOUND' if violations else 'ALL_INVARIANTS_PASSED', flush=True)
    raise SystemExit(1 if violations else 0)


if __name__ == '__main__':
    main()
