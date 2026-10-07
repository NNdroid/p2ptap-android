#!/usr/bin/env bash
# Check that every translated locale of a module carries exactly the same
# resource names as that module's default resources, and that the set of
# format placeholders (%1$s, %d, ...) in each translation matches the default.
#
# Usage: scripts/check-i18n.sh [MODULE ...]
#   MODULE defaults to: mobile tv core
#
# A module is <MODULE>/src/main/res, with defaults in values/ and translations
# in values-<locale>/. Only locale qualifiers are read; configuration
# qualifiers (land, night, v23, w600dp) are skipped. Resource file names are
# discovered rather than assumed, because :core calls its file core_strings.xml.
#
# Exits non-zero if any module/locale pair is incomplete or a placeholder set
# has drifted from the default.

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

modules=("$@")
[ "${#modules[@]}" -gt 0 ] || modules=(mobile tv core)

failures=0

# Locale dirs are values-<2 letters>[-<2..4 letters>]: this accepts values-de
# and values-zh-rCN while rejecting values-land, values-night, values-v23 and
# values-w600dp.
is_locale() {
    case "$1" in
        values-[a-z][a-z] | values-[a-z][a-z]-[A-Za-z][A-Za-z][A-Za-z] | values-[a-z][a-z]-[A-Za-z][A-Za-z][A-Za-z][A-Za-z])
            return 0 ;;
        *) return 1 ;;
    esac
}

# Emit one "name<TAB>placeholder-set" line per resource across the given
# files. Handles single-line <string> and block resources (<plurals>, arrays).
parse_resources() {
    awk '
        function flush(   body, ph, tok) {
            if (name == "") return
            body = cur
            gsub(/<[^>]*>/, " ", body)
            ph = ""
            while (match(body, /%[0-9]+\$[sd]|%[sd]/)) {
                tok = substr(body, RSTART, RLENGTH)
                body = substr(body, 1, RSTART - 1)
                if (index(ph, tok) == 0) ph = (ph == "" ? tok : ph " " tok)
            }
            print name "\t" ph
            name = ""; cur = ""
        }
        /<(string|plurals|string-array|integer-array) name="/ {
            flush()
            if (match($0, /name="[^"]+"/)) name = substr($0, RSTART + 6, RLENGTH - 7)
            cur = $0
            inres = ($0 ~ /<\/(string|plurals|string-array|integer-array)>/) ? 0 : 1
            next
        }
        inres {
            cur = cur " " $0
            if ($0 ~ /<\/(plurals|string-array|integer-array)>/) inres = 0
        }
        END { flush() }
    ' "$@"
}

# Report apostrophes inside string values that are not escaped as \'. AAPT2
# rejects them at build time with "Invalid unicode escape sequence", so it is
# cheaper to catch them in review. Apostrophes in XML comments and in tag
# markup are ignored; the q = sprintf form keeps this source free of literal
# apostrophes.
find_unescaped_apostrophes() {
    awk '
        BEGIN { comment = 0; q = sprintf("%c", 39) }
        {
            s = $0
            out = ""
            for (i = 1; i <= length(s); i++) {
                if (comment) {
                    if (substr(s, i, 3) == "-->") { comment = 0; i += 2 }
                    continue
                }
                if (substr(s, i, 4) == "<!--") { comment = 1; i += 3; continue }
                if (substr(s, i, 1) == "<") {
                    j = index(substr(s, i), ">")
                    if (j > 0) { i += j - 1 }
                    continue
                }
                out = out substr(s, i, 1)
            }
            for (k = 1; k <= length(out); k++)
                if (substr(out, k, 1) == q && substr(out, k - 1, 1) != "\\")
                    print FILENAME ":" FNR ": " out
        }
    ' "$@"
}

for module in "${modules[@]}"; do
    res="$ROOT/$module/src/main/res"
    [ -d "$res" ] || { echo "WARN  $module: no src/main/res, skipping"; continue; }

    default_dir="$res/values"
    [ -d "$default_dir" ] || { echo "WARN  $module: no values/, skipping"; continue; }
    ls "$default_dir"/*.xml >/dev/null 2>&1 \
        || { echo "WARN  $module: no resources in values/, skipping"; continue; }

    declare -A default_ph=()
    default_names=()
    while IFS=$'\t' read -r n p; do
        [ -n "$n" ] || continue
        default_ph["$n"]=$p
        default_names+=("$n")
    done < <(parse_resources "$default_dir"/*.xml | sort)

    echo "-- $module: ${#default_names[@]} default resources"

    default_apos=$(find_unescaped_apostrophes "$default_dir"/*.xml)
    if [ -n "$default_apos" ]; then
        echo "FAIL  default (values/): apostrophe in a string value is not escaped as \\'"
        printf '      %s\n' "$default_apos"
        failures=$((failures + 1))
    fi

    locales=()
    for d in "$res"/*/; do
        b=$(basename "$d")
        is_locale "$b" && locales+=("$b")
    done
    locales=($(printf '%s\n' "${locales[@]}" | sort))
    [ "${#locales[@]}" -gt 0 ] || { echo "      (no locale directories)"; continue; }

    for locale in "${locales[@]}"; do
        dir="$res/$locale"

        if ! ls "$dir"/*.xml >/dev/null 2>&1; then
            echo "FAIL  $locale: locale directory exists but holds no resource file"
            failures=$((failures + 1))
            continue
        fi

        locale_apos=$(find_unescaped_apostrophes "$dir"/*.xml)
        if [ -n "$locale_apos" ]; then
            echo "FAIL  $locale: apostrophe in a string value is not escaped"
            printf '      %s\n' "$locale_apos"
            failures=$((failures + 1))
        fi

        declare -A locale_ph=()
        locale_names=()
        while IFS=$'\t' read -r n p; do
            [ -n "$n" ] || continue
            locale_ph["$n"]=$p
            locale_names+=("$n")
        done < <(parse_resources "$dir"/*.xml | sort)

        missing=""
        extra=""
        badfmt=""

        for n in "${default_names[@]}"; do
            [ -n "${locale_ph[$n]+x}" ] || missing+="$n "
        done
        for n in "${locale_names[@]}"; do
            [ -n "${default_ph[$n]+x}" ] || extra+="$n "
        done
        for n in "${default_names[@]}"; do
            [ -n "${locale_ph[$n]+x}" ] || continue
            if [ "${default_ph[$n]}" != "${locale_ph[$n]}" ]; then
                badfmt+="\"$n\" default=[${default_ph[$n]}] $locale=[${locale_ph[$n]}]; "
            fi
        done

        if [ -z "$missing$extra$badfmt" ]; then
            echo "OK    $locale"
        else
            failures=$((failures + 1))
            echo "FAIL  $locale"
            [ -n "$missing" ] && echo "      missing: $missing"
            [ -n "$extra" ] && echo "      extra:   $extra"
            [ -n "$badfmt" ] && echo "      fmt:     $badfmt"
        fi
    done
done

echo
if [ "$failures" -gt 0 ]; then
    echo "i18n check FAILED: $failures module/locale pair(s)"
    exit 1
fi
echo "i18n check passed"
