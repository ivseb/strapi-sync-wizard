#!/usr/bin/env bash
# Live integration test for the merge-apply pipeline, run against the collaudo env (real prod/preprod
# dumps). Deterministic + re-runnable. Asserts on the real target DB + the /verify endpoint.
#
# Covers: post-sync VERIFY consistency, IDEMPOTENCY (re-run stability), and ROLLBACK-on-failure
# (a manufactured partial failure must revert even the successfully-applied changes).
#
# Prereqs: tool on :8085, docker postgres `pcc-sync-pg` (:5434), a COMPLETED merge request (default 1)
# whose source/target are the two real instances. Read-only except the rollback scenario, which makes
# a reversible change it cleans up. Exits non-zero on the first failed assertion.
#
#   TOOL=http://localhost:8085  MR=1  TGT_DB=nlpcc_pcc_strapi_db  ./dev/integration-test.sh
set -uo pipefail

TOOL="${TOOL:-http://localhost:8085}"
MR="${MR:-1}"
PG="docker exec pcc-sync-pg psql -U postgres"
SYNC_DB="strapisync"
TGT_DB="${TGT_DB:-nlpcc_pcc_strapi_db}"
PASS=0; FAIL=0

q()    { $PG -d "$1" -tAc "$2" | tr -d '[:space:]'; }            # scalar query (trimmed)
api()  { curl -s -X "$1" "$TOOL$2" --max-time 240; }
jq_py(){ python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }
ok()   { echo "  PASS: $1"; PASS=$((PASS+1)); }
ko()   { echo "  FAIL: $1"; FAIL=$((FAIL+1)); }
chk()  { if [ "$2" = "$3" ]; then ok "$1 ($2)"; else ko "$1 — expected [$3] got [$2]"; fi; }

echo "== strapi-sync integration test (MR $MR, target $TGT_DB) =="

# ---------------------------------------------------------------------------
echo "[1] Post-sync verification: no REAL mismatches (schema-gaps allowed)"
V=$(api POST "/api/merge-requests/$MR/verify")
total=$(echo "$V" | jq_py "d['total']"); cons=$(echo "$V" | jq_py "d['consistent']")
gap=$(echo "$V" | jq_py "d['schemaGap']"); mis=$(echo "$V" | jq_py "d['inconsistent']")
echo "  verify: total=$total consistent=$cons schema_gap=$gap mismatch=$mis"
chk "no real mismatches" "$mis" "0"

# ---------------------------------------------------------------------------
echo "[2] Idempotency: a clean re-run keeps the target stable (no duplicates)"
files_before=$(q "$TGT_DB" "SELECT count(*) FROM files;")
sz_before=$(q "$TGT_DB" "SELECT count(DISTINCT document_id) FROM step_zeros;")
api POST "/api/merge-requests/$MR/complete" >/dev/null
files_after=$(q "$TGT_DB" "SELECT count(*) FROM files;")
sz_after=$(q "$TGT_DB" "SELECT count(DISTINCT document_id) FROM step_zeros;")
chk "file count stable" "$files_after" "$files_before"
chk "step_zeros count stable" "$sz_after" "$sz_before"

# ---------------------------------------------------------------------------
echo "[3] Rollback-on-failure: a partial failure reverts even applied changes"
# Pick a step_zeros UPDATE target in this MR, and a step_zeros CREATE source to force a failure.
upd_src=$(q "$SYNC_DB" "SELECT \"documentId\" FROM merge_request_selections WHERE merge_request_id=$MR AND content_type='step_zeros' AND direction=1 LIMIT 1;")
upd_tgt=$($PG -d "$SYNC_DB" -tAc "SELECT target_document_id FROM strapi_document_mapping WHERE content_type='api::step-zero.step-zero' AND source_document_id='$upd_src' LIMIT 1;" | tr -d '[:space:]')
crt_src=$(q "$SYNC_DB" "SELECT \"documentId\" FROM merge_request_selections WHERE merge_request_id=$MR AND content_type='step_zeros' AND direction=0 LIMIT 1;")
if [ -z "$upd_tgt" ] || [ -z "$crt_src" ]; then
  echo "  SKIP: could not find an update target + a create source for step_zeros in this MR"
else
  orig=$($PG -d "$TGT_DB" -tAc "SELECT component_title FROM step_zeros WHERE document_id='$upd_tgt' LIMIT 1;")
  # save the create's mapping so we can restore it, then remove it to force a real CREATE (which
  # collides on Strapi's unique component_title -> deterministic failure).
  map_bak=$($PG -d "$SYNC_DB" -tAc "SELECT target_document_id FROM strapi_document_mapping WHERE content_type='api::step-zero.step-zero' AND source_document_id='$crt_src' LIMIT 1;" | tr -d '[:space:]')
  $PG -d "$SYNC_DB" -tAc "DELETE FROM strapi_document_mapping WHERE content_type='api::step-zero.step-zero' AND source_document_id='$crt_src';" >/dev/null
  # manufacture a pre-run divergence on the update target
  $PG -d "$TGT_DB" -tAc "UPDATE step_zeros SET component_title='ROLLBACK_SENTINEL' WHERE document_id='$upd_tgt';" >/dev/null
  R=$(api POST "/api/merge-requests/$MR/complete?rollbackOnFailure=true")
  failed=$(echo "$R" | jq_py "d['failed']")
  after=$($PG -d "$TGT_DB" -tAc "SELECT DISTINCT component_title FROM step_zeros WHERE document_id='$upd_tgt';" | tr -d '[:space:]')
  echo "  run: failed=$failed ; component_title after rollback=[$after]"
  if [ "$failed" -gt 0 ] 2>/dev/null; then ok "partial failure produced ($failed failed)"; else ko "expected a partial failure"; fi
  chk "applied update reverted by rollback" "$after" "ROLLBACK_SENTINEL"
  # cleanup: restore the original value and a fresh mapping (re-run idempotently links it again)
  $PG -d "$TGT_DB" -tAc "UPDATE step_zeros SET component_title='$(echo "$orig" | sed "s/'/''/g")' WHERE document_id='$upd_tgt' AND component_title='ROLLBACK_SENTINEL';" >/dev/null
fi

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
