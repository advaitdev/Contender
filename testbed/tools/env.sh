# Shared paths for the testbed scripts. Source this file; don't run it.
TESTBED="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="${CONTENDER_REPO:-$(cd "$TESTBED/.." && pwd)}"
SERVER="${CONTENDER_TEST_SERVER:-$TESTBED/server}"
WORK="${CONTENDER_TEST_WORK:-$TESTBED/.work}"
mkdir -p "$WORK"
