"""Apply the curated Lucene SPI overrides required by deployable mongot jars."""

import os
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path


def apply_overrides(jar_path: Path, overrides_path: Path) -> None:
    """Validate every override before atomically replacing the archive."""
    overrides: dict[str, list[tuple[str, str]]] = {}
    for line in overrides_path.read_text().splitlines():
        row = line.partition("#")[0].strip()
        if not row:
            continue
        fields = row.split()
        if len(fields) != 3 or not fields[0].startswith("META-INF/services/"):
            raise ValueError("Invalid SPI override row")
        resource, source, target = fields
        if source == target:
            raise ValueError("SPI override must replace a different provider")
        overrides.setdefault(resource, []).append((source, target))

    replacements = {}
    with zipfile.ZipFile(jar_path) as original:
        for resource, rows in overrides.items():
            text = original.read(resource).decode("utf-8")
            lines = text.splitlines()
            providers = {line.partition("#")[0].strip() for line in lines}
            for source, target in rows:
                if source not in providers or target not in providers:
                    raise ValueError(f"SPI override providers are missing from {resource}")
            stripped = {source for source, _ in rows}
            replacements[resource] = (
                "\n".join(line for line in lines if line.partition("#")[0].strip() not in stripped)
                + "\n"
            ).encode("utf-8")

        temporary = None
        try:
            with tempfile.NamedTemporaryFile(dir=jar_path.parent, delete=False) as output:
                temporary = Path(output.name)
            with zipfile.ZipFile(temporary, "w") as rewritten:
                rewritten.comment = original.comment
                for entry in original.infolist():
                    if entry.filename in replacements:
                        rewritten.writestr(entry, replacements[entry.filename])
                    else:
                        with original.open(entry) as source, rewritten.open(entry, "w") as target:
                            shutil.copyfileobj(source, target, 1024 * 1024)
            os.chmod(temporary, jar_path.stat().st_mode & 0o777)
            os.replace(temporary, jar_path)
            temporary = None
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: apply_spi_service_overrides JAR OVERRIDES.tsv")
    apply_overrides(Path(sys.argv[1]), Path(sys.argv[2]))
