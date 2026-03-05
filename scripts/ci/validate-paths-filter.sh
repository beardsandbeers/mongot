#!/usr/bin/env bash
set -euo pipefail
#
# Validates that every tracked top-level repo entry is accounted for by either
# the source_code_paths list in base_tasks.yml or the exclusion file. Fails if
# any entry is missing from both, which means a new file/directory was added
# without updating path filtering config.
#
# Also validates that the source_code_paths in .evergreen.yml matches the one
# in base_tasks.yml (both must stay in sync).

REPO_ROOT="$(git rev-parse --show-toplevel)"
YAML_BASE="$REPO_ROOT/conf/evergreen/base_tasks.yml"
YAML_EVG="$REPO_ROOT/.evergreen.yml"
EXCLUDE_FILE="$REPO_ROOT/conf/evergreen/paths-exclude.txt"

for f in "$YAML_BASE" "$YAML_EVG" "$EXCLUDE_FILE"; do
  if [ ! -f "$f" ]; then
    echo "ERROR: $f not found"
    exit 1
  fi
done

# Extract source_code_paths entries from a YAML file.
# Handles comments between list items. Stops at first non-list, non-comment line.
extract_yaml_paths() {
  awk '
    /paths:[[:space:]]*&source_code_paths/ { capture=1; next }
    capture && /^[[:space:]]*#/ { next }
    capture && /^[[:space:]]*$/ { next }
    capture && /^[[:space:]]*-[[:space:]]/ {
      sub(/^[[:space:]]*-[[:space:]]*/, "")
      gsub(/"/, "")
      print
      next
    }
    capture { exit }
  ' "$1"
}

# Load exclusion patterns (strip comments, blank lines, and carriage returns).
load_exclusions() {
  tr -d '\r' < "$EXCLUDE_FILE" \
    | grep -v '^[[:space:]]*#' \
    | grep -v '^[[:space:]]*$' \
    | sed 's/[[:space:]]*$//' \
    || true
}

# Check if a top-level entry matches a source_code_paths pattern.
# Directories match "dir/**" patterns; files match exact names or globs.
matches_source_paths() {
  local entry="$1"
  local entry_type="$2"

  while IFS= read -r pattern; do
    [ -z "$pattern" ] && continue

    if [ "$entry_type" = "tree" ]; then
      local dir_name="${pattern%/\*\*}"
      if [ "$dir_name" != "$pattern" ] && [ "$entry" = "$dir_name" ]; then
        return 0
      fi
    else
      if [ "$entry" = "$pattern" ]; then
        return 0
      fi
      # shellcheck disable=SC2254
      case "$entry" in
        $pattern) return 0 ;;
      esac
    fi
  done <<< "$source_paths"

  return 1
}

# Check if a top-level entry matches an exclusion pattern.
matches_exclusions() {
  local entry="$1"

  while IFS= read -r pattern; do
    [ -z "$pattern" ] && continue
    if [ "$entry" = "$pattern" ]; then
      return 0
    fi
    # shellcheck disable=SC2254
    case "$entry" in
      $pattern) return 0 ;;
    esac
  done <<< "$exclusions"

  return 1
}

# --- Validate source_code_paths sync between the two YAML files ---
base_paths="$(extract_yaml_paths "$YAML_BASE" | sort)"
evg_paths="$(extract_yaml_paths "$YAML_EVG" | sort)"

if [ -z "$base_paths" ]; then
  echo "ERROR: Could not extract source_code_paths from $YAML_BASE"
  echo "Expected to find 'paths: &source_code_paths' followed by list entries."
  exit 1
fi

if [ -z "$evg_paths" ]; then
  echo "ERROR: Could not extract source_code_paths from $YAML_EVG"
  echo "Expected to find 'paths: &source_code_paths' followed by list entries."
  exit 1
fi

if [ "$base_paths" != "$evg_paths" ]; then
  echo "ERROR: source_code_paths in base_tasks.yml and .evergreen.yml are out of sync."
  echo ""
  echo "base_tasks.yml has:"
  echo "$base_paths"
  echo ""
  echo ".evergreen.yml has:"
  echo "$evg_paths"
  echo ""
  echo "Both files must have identical source_code_paths lists."
  exit 1
fi

source_paths="$base_paths"
exclusions="$(load_exclusions)"

# --- Validate all top-level entries are accounted for ---
missing=()

while IFS=$'\t' read -r type entry; do
  if ! matches_source_paths "$entry" "$type" && ! matches_exclusions "$entry"; then
    missing+=("$entry")
  fi
done < <(git ls-tree HEAD --name-only -t | while read -r name; do
  obj_type=$(git cat-file -t "HEAD:$name" 2>/dev/null || echo "blob")
  printf '%s\t%s\n' "$obj_type" "$name"
done)

if [ ${#missing[@]} -gt 0 ]; then
  echo "ERROR: The following top-level entries are not covered by source_code_paths"
  echo "or paths-exclude.txt. Each must be added to one of:"
  echo "  - conf/evergreen/base_tasks.yml (source_code_paths anchor) — if it affects build/tests"
  echo "  - conf/evergreen/paths-exclude.txt — if it's safe to skip E2E for"
  echo ""
  for entry in "${missing[@]}"; do
    echo "  - $entry"
  done
  exit 1
fi

echo "OK: All top-level entries are accounted for in source_code_paths or paths-exclude.txt."
echo "OK: source_code_paths in base_tasks.yml and .evergreen.yml are in sync."
