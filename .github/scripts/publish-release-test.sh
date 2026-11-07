#!/bin/bash
# Fixture tests for publish-release.sh: stub aapt/apksigner, canned API responses,
# mutation log assertions. No network, no real releases. Exits 1 on any failure.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
PASS=0; FAIL=0

CERT_OK="6b718bd63aff2c39974ff0bcfbfd9ef315e2af4026c90062020beb1dc1880a0f"
CERT_BAD="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

mk_tools() { # $1=cert-digest  $2=versionCode  $3=versionName ; prints ANDROID_HOME stub
  local dir="$WORK/ah-${#FAIL}-$2-$3-$RANDOM"
  mkdir -p "$dir/build-tools/35.0.0"
  cat > "$dir/build-tools/35.0.0/aapt" <<EOF
#!/bin/bash
echo "package: name='app.roidgram' versionCode='$2' versionName='$3'"
EOF
  cat > "$dir/build-tools/35.0.0/apksigner" <<EOF
#!/bin/bash
echo "Signer #1 certificate DN: CN=test"
echo "Signer #1 certificate SHA-256 digest: $1"
EOF
  chmod +x "$dir/build-tools/35.0.0/aapt" "$dir/build-tools/35.0.0/apksigner"
  echo "$dir"
}

run_case() { # $1=name  $2=fixture-setup-fn  $3=expected-exit  $4=expected-mutations-count
  local name="$1" setup="$2" want_exit="$3" want_mut="$4"
  local fix="$WORK/fix-$name"; mkdir -p "$fix"
  $setup "$fix"
  local ahome; ahome=$(mk_tools "$CASE_CERT" "$CASE_VC" "$CASE_VN")
  echo fixture-apk-bytes > "$fix/app.apk"
  ( cd "$fix" && ANDROID_HOME="$ahome" ROIDGRAM_FIXTURE_DIR="$fix" \
      GITHUB_REPOSITORY="manuelmania93-svg/Roidgram" GITHUB_SHA="deadbeef" \
      EXPECTED_CERT_SHA256="$CERT_OK" \
      bash "$ROOT/publish-release.sh" app.apk > out.log 2>&1 )
  local got_exit=$?
  local got_mut=0
  [ -f "$fix/mutations.log" ] && got_mut=$(wc -l < "$fix/mutations.log")
  if [ "$got_exit" = "$want_exit" ] && [ "$got_mut" = "$want_mut" ]; then
    PASS=$((PASS+1)); echo "PASS $name"
  else
    FAIL=$((FAIL+1)); echo "FAIL $name (exit=$got_exit want=$want_exit, mutations=$got_mut want=$want_mut)"; sed 's/^/    /' "$fix/out.log" | tail -5
  fi
}

good_checks() { cat > "$1/check-runs.json" <<'EOF'
{"check_runs":[
 {"name":"build","status":"completed","conclusion":"success"},
 {"name":"Publish release","status":"in_progress","conclusion":null},
 {"name":"verify (35)","status":"completed","conclusion":"success"}]}
EOF
}

CASE_CERT=$CERT_OK CASE_VC=2042 CASE_VN=0.3.0
setup_happy() { good_checks "$1"; echo '{"assets":[{"name":"roidgram-update.json","browser_download_url":"https://x/prev.json"}]}' > "$1/latest-release.json"
                echo '{"versionCode":2000}' > "$1/prev-manifest.json"; }
run_case happy setup_happy 0 4

setup_tag_exists() { good_checks "$1"; echo '{"isDraft":false}' > "$1/release-view.json"; }
run_case tag-exists-skips setup_tag_exists 0 0

setup_stale_draft() { good_checks "$1"; echo '{"isDraft":true}' > "$1/release-view.json"; }
run_case stale-draft-aborts setup_stale_draft 1 0

setup_nonmono() { good_checks "$1"; echo '{"isDraft":false,"_absent":true}' > /dev/null
                  echo '{"assets":[{"name":"roidgram-update.json","browser_download_url":"https://x/prev.json"}]}' > "$1/latest-release.json"
                  echo '{"versionCode":5000}' > "$1/prev-manifest.json"; }
run_case non-monotonic-aborts setup_nonmono 1 0

setup_badcert() { good_checks "$1"; }
CASE_CERT=$CERT_BAD CASE_VC=2042 CASE_VN=0.3.0
run_case bad-cert-aborts setup_badcert 1 0
CASE_CERT=$CERT_OK CASE_VC=2042 CASE_VN=0.3.0

setup_failing_check() { cat > "$1/check-runs.json" <<'EOF'
{"check_runs":[
 {"name":"build","status":"completed","conclusion":"success"},
 {"name":"Publish release","status":"in_progress","conclusion":null},
 {"name":"verify (35)","status":"completed","conclusion":"failure"}]}
EOF
}
CASE_CERT=$CERT_OK run_case failing-check-aborts setup_failing_check 1 0

setup_no_build_check() { cat > "$1/check-runs.json" <<'EOF'
{"check_runs":[{"name":"Publish release","status":"in_progress","conclusion":null}]}
EOF
}
run_case vacuous-checks-abort setup_no_build_check 1 0

setup_pending_check() { cat > "$1/check-runs.json" <<'EOF'
{"check_runs":[
 {"name":"build","status":"completed","conclusion":"success"},
 {"name":"Publish release","status":"in_progress","conclusion":null},
 {"name":"verify (30)","status":"queued","conclusion":null}]}
EOF
}
run_case pending-check-aborts setup_pending_check 1 0

setup_duplicate_builds() { good_checks "$1"; python3 - "$1/check-runs.json" <<'PYCASE'
import json,sys
p=sys.argv[1];d=json.load(open(p));d['check_runs'].append({'name':'build','status':'completed','conclusion':'success'});json.dump(d,open(p,'w'))
PYCASE
}
run_case duplicate-successful-builds setup_duplicate_builds 0 4

echo "== $PASS passed, $FAIL failed =="
[ "$FAIL" = "0" ]
