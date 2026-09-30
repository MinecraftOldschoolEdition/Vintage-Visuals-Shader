#!/usr/bin/env python3
"""Build reproducible pack and companion engine ZIPs without a wrapper directory."""
import argparse
import hashlib
import json
import re
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


def archive(output, root, paths):
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, "w", compression=ZIP_DEFLATED, compresslevel=9) as zipped:
        for path in sorted(paths):
            if path.is_symlink() or not path.is_file():
                raise ValueError(f"Expected an ordinary file: {path}")
            name = path.relative_to(root).as_posix()
            info = ZipInfo(name, date_time=(2026, 9, 30, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            zipped.writestr(info, path.read_bytes(), compresslevel=9)
    with ZipFile(output) as zipped:
        if zipped.testzip() is not None:
            raise ValueError(f"Corrupt ZIP: {output}")
        names = zipped.namelist()
        if any(name.startswith(("/", ".git/")) or ".." in Path(name).parts for name in names):
            raise ValueError("Unsafe ZIP path")
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix(output.suffix + ".sha256").write_text(f"{digest}  {output.name}\n")
    print(f"{output}: {len(paths)} files, SHA-256 {digest}")


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=root / "dist")
    args = parser.parse_args()
    version = (root / "VERSION").read_text().strip()
    if not re.fullmatch(r"\d+\.\d+(?:\.\d+)?", version):
        raise ValueError("VERSION must be a numeric release version")
    manifest = json.loads((root / "assets/minecraft/shaders/pack.json").read_text())
    json.loads((root / "pack.mcmeta").read_text())
    pack_name = manifest["name"].replace(" ", "-")
    asset_root = root / "assets"
    if any(p.is_symlink() for p in asset_root.rglob("*")):
        raise ValueError("Pack assets cannot contain symbolic links")
    files = [p for p in asset_root.rglob("*") if p.is_file()]
    files += [root / name for name in ("pack.mcmeta", "README.md", "CHANGELOG.md", "PERFORMANCE.md", "VERSION")]
    output = args.output_dir / f"{pack_name}-{version}.zip"
    archive(output, root, files)
    with ZipFile(output) as zipped:
        names = zipped.namelist()
        if "pack.mcmeta" not in names or "assets/minecraft/shaders/pack.json" not in names:
            raise ValueError("Pack metadata/assets must be at the ZIP root")
    engine_files = [p for p in (root / "engine").rglob("*") if p.is_file()]
    archive(args.output_dir / f"GI-engine-update-{version}.zip", root, engine_files)


if __name__ == "__main__":
    main()
