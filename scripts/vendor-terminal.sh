#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "$0")/.." && pwd)
asset_dir="$project_dir/app/src/main/assets/terminal"
work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT
npm pack @xterm/xterm@5.5.0 @xterm/addon-fit@0.10.0 --pack-destination "$work_dir" --silent
mkdir "$work_dir/xterm" "$work_dir/fit"
tar -xzf "$work_dir/xterm-xterm-5.5.0.tgz" -C "$work_dir/xterm"
tar -xzf "$work_dir/xterm-addon-fit-0.10.0.tgz" -C "$work_dir/fit"
npx --yes esbuild@0.25.5 "$work_dir/xterm/package/lib/xterm.js" --target=chrome61 --minify --legal-comments=inline --outfile="$asset_dir/xterm.js"
cp "$work_dir/xterm/package/css/xterm.css" "$asset_dir/xterm.css"
cp "$work_dir/xterm/package/LICENSE" "$asset_dir/XTERM-LICENSE.txt"
cp "$work_dir/fit/package/lib/addon-fit.js" "$asset_dir/addon-fit.js"
