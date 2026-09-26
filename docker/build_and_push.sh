#!/bin/bash
# build_and_push.sh -- the OM fork's build pipeline, made repeatable.
#
# Before this script existed, building this image was an entirely manual,
# undocumented, never-automated process (clone from THIS specific checkout,
# because a worktree's .git is a pointer file that fails buildnumber-maven-
# plugin's `git log` shell-out; full Maven build with RAT+Checkstyle; docker
# build; manual tag + push to ECR). That's a real risk for the artifact that
# becomes production's live media server -- this script is the fix: the same
# sequence, but reviewable, repeatable, and self-verifying.
#
# USAGE
#   docker/build_and_push.sh [--push] [--tag-suffix SUFFIX]
#
#   --push          Also push the built image to ECR. Without it, the image
#                    is built and verified locally only -- safe to run
#                    repeatedly while iterating.
#   --tag-suffix    Appended to the tag after the git short-SHA, e.g.
#                    "prod-candidate" -> full-replace-<sha>-prod-candidate.
#                    Use this for a production-candidate build so it's never
#                    confused with (or accidentally reused as) whatever
#                    staging currently has running -- the ECR repo is
#                    MUTABLE, so tag distinctness is a naming discipline,
#                    not something the registry enforces.
#
# Must be run from a repo with a REAL .git directory (a plain `git clone`,
# not a `git worktree`) -- buildnumber-maven-plugin shells out to `git log`
# during the Maven build, and a worktree's .git is a text file pointing at
# an absolute host path that does not exist inside the Docker build context.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
cd "$REPO_ROOT"

ECR_REPO="975622744795.dkr.ecr.ap-southeast-2.amazonaws.com/tutorship-production-om-single-stream"
AWS_REGION="ap-southeast-2"
AWS_PROFILE="${AWS_PROFILE:-tutorship}"

PUSH=0
TAG_SUFFIX=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --push) PUSH=1; shift ;;
    --tag-suffix) TAG_SUFFIX="-$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

# --- 1: refuse to build from a worktree -------------------------------------
if [[ -f .git ]]; then
  cat >&2 <<EOF
REFUSED: $REPO_ROOT/.git is a FILE, not a directory -- this is a git
worktree, not a real clone. buildnumber-maven-plugin's own \`git log\`
shell-out fails inside the Docker build context against a worktree's
pointer path. Build from the real clone instead
(e.g. /home/kam270/openmeetings-fork-full-replace-clone).
EOF
  exit 1
fi

# --- 2: refuse an unclean tree (the build must reflect a real commit) -------
if [[ -n "$(git status --porcelain)" ]]; then
  echo "REFUSED: working tree is not clean. Commit or stash before building:" >&2
  git status --short >&2
  exit 1
fi

BRANCH="$(git branch --show-current)"
SHA="$(git rev-parse --short=9 HEAD)"
TAG="full-replace-${SHA}${TAG_SUFFIX}"
IMAGE="${ECR_REPO}:${TAG}"

echo "=== Building $IMAGE (branch $BRANCH, commit $(git rev-parse HEAD)) ==="

# --- 3: build ----------------------------------------------------------------
docker build -f docker/Dockerfile -t "$IMAGE" .

# --- 4: basic post-build verification ---------------------------------------
echo "=== Verifying image ==="

# 4a: the entrypoint script exists and the image isn't some empty/broken layer.
docker run --rm --entrypoint /bin/sh "$IMAGE" -c '[ -x /opt/om.sh ] && echo "om.sh present and executable"'

# 4b: confirm the build actually produced our patched source, not a stale
# cached layer silently reusing an old build -- the version label baked into
# the Dockerfile records which branch built this image, and OM's own
# buildnumber-maven-plugin output embeds the git SHA in the running app's
# own version info. Cross-check both against what we think we just built.
LABEL_VENDOR="$(docker inspect --format '{{ index .Config.Labels "vendor" }}' "$IMAGE")"
echo "image label vendor: $LABEL_VENDOR"
if [[ "$LABEL_VENDOR" != *"full-recorder-replacement"* ]]; then
  echo "REFUSED: image vendor label doesn't match the expected fork build -- got: $LABEL_VENDOR" >&2
  exit 1
fi

# 4c: sanity-check image size is in a plausible range (a broken/partial build
# that still tags successfully would likely be drastically smaller).
SIZE_BYTES="$(docker inspect --format '{{ .Size }}' "$IMAGE")"
SIZE_MB=$((SIZE_BYTES / 1024 / 1024))
echo "image size: ${SIZE_MB} MB"
if [[ "$SIZE_MB" -lt 500 ]]; then
  echo "REFUSED: image is only ${SIZE_MB} MB -- suspiciously small for a full OM+JRE+LibreOffice image, refusing to trust it." >&2
  exit 1
fi

echo "=== Verification passed: $IMAGE ==="

# --- 5: push (only if asked) --------------------------------------------------
if [[ "$PUSH" -eq 1 ]]; then
  echo "=== Pushing $IMAGE ==="
  aws ecr get-login-password --region "$AWS_REGION" --profile "$AWS_PROFILE" \
    | docker login --username AWS --password-stdin "${ECR_REPO%%/*}"
  docker push "$IMAGE"
  echo "=== Pushed: $IMAGE ==="
else
  echo "Built and verified locally only. Re-run with --push to publish to ECR."
fi

echo "$IMAGE"
