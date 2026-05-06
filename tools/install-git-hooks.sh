#!/bin/sh
set -eu

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

git config core.hooksPath tools/git-hooks
chmod +x tools/git-hooks/pre-push

echo "Installed git hooks from tools/git-hooks."
echo "Pre-push now runs: ./gradlew ktlintCheck"
