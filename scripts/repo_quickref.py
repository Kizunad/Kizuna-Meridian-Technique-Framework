"""从 Fabric 清单、源码路径和 Framework 装配清单生成仓库速查。"""

import argparse
import difflib
import json
from pathlib import Path
import re
import subprocess
import sys


FRAMEWORK = "Kizuna-Meridian-Mod-Framework"
SECTION = "## 速查"
GENERATED = "### 自动生成"
MANUAL = "### 常见坑"
BLOCK = re.compile(
    rf"^{SECTION}\n\n{GENERATED}\n\n(?P<body>.*?)(?=^### |^## |\Z)",
    re.MULTILINE | re.DOTALL,
)
SOURCE = re.compile(r"(?:^|/)src/(\w+)/(?:java|kotlin|resources|proto)/")
DATA = re.compile(r"(?:^|/)src/\w+/resources/(?:data|assets)/[^/]+/")


def git(root: Path, *args: str) -> str:
    return subprocess.check_output(["git", "-C", str(root), *args], encoding="utf-8")


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def code_list(items) -> str:
    return "、".join(f"`{item}`" for item in items) or "无"


def manifest_lines(root: Path, files: list[str]) -> list[str]:
    lines = []
    for file in files:
        if not file.endswith("src/main/resources/fabric.mod.json"):
            continue
        manifest = read_json(root / file)
        lines.append(f"- Fabric 模组 `{manifest['id']}`，清单 `{file}`")
        entries = manifest.get("entrypoints", {})
        for key, values in sorted(entries.items()):
            targets = [value["value"] if isinstance(value, dict) else value for value in values]
            lines.append(f"  - `{key}` → {code_list(targets)}")
        if not entries:
            lines.append("  - 入口类无")
    return lines


def source_lines(root: Path) -> list[str]:
    listed = git(root, "ls-files", "-co", "--exclude-standard", "-z").split("\0")
    files = sorted({file for file in listed if file and (root / file).is_file()})
    data = set()
    assets = set()
    protobuf_data = set()
    configs = set()
    tests = set()
    for file in files:
        source = SOURCE.search(file)
        if not source:
            continue
        if "test" in source[1].lower():
            tests.add(file[:source.start(1)] + source[1] + "/")
        dataset = DATA.search(file)
        if dataset:
            directories = data if "/resources/data/" in dataset[0] else assets
            directories.add(file[:dataset.end()])
        path = Path(file)
        if path.suffix in {".txtpb", ".binpb"}:
            protobuf_data.add(file)
        if re.search(r"(?:^|/)src/main/resources/[^/]+\.toml$", file):
            configs.add(file)
    return manifest_lines(root, files) + [
        f"- 数据目录：{code_list(sorted(data))}",
        f"- 资源目录：{code_list(sorted(assets))}",
        f"- Protobuf 数据文件（含功法目录）：{code_list(sorted(protobuf_data))}",
        f"- 随包 TOML：{code_list(sorted(configs))}",
        f"- 测试源集：{code_list(sorted(tests))}",
    ]


def kz_lines(entry: dict) -> list[str]:
    kz = entry.get("kz", {})
    unit = " ".join(kz.get("test", ["test", "build"]))
    lines = [
        f"- `kz` 装配 ID：`{entry['id']}`（`--with` 使用工作树路径）",
        f"- `kz test --part unit`：`{unit}`",
    ]
    games = kz.get("gametest", [])
    if games:
        lines.append("- `kz test --part gametest`")
        for game in games:
            task = " ".join([game["task"], *game.get("arguments", [])])
            run_dir = game.get("runDirectory")
            suffix = f"，运行目录 `{run_dir}`" if run_dir else ""
            lines.append(f"  - `{task}`{suffix}")
    else:
        lines.append("- `kz test --part gametest`：装配清单未登记")
    lines.append(f"- `kz client` 项目：{code_list(sorted(kz.get('client', {})))}")
    return lines


def update(root: Path, generated: str, check: bool) -> int:
    agents = root / "AGENTS.md"
    content = agents.read_text(encoding="utf-8")
    block = BLOCK.search(content)
    if block and content.startswith(MANUAL + "\n", block.end()):
        old = block["body"]
        if old == generated:
            print(f"{root.name} 速查一致")
            return 0
        if check:
            sys.stderr.writelines(difflib.unified_diff(
                old.splitlines(keepends=True), generated.splitlines(keepends=True),
                fromfile=str(agents), tofile="重新生成",
            ))
            return 1
        updated = content[:block.start("body")] + generated + content[block.end():]
    elif check or re.search(rf"^{SECTION}$", content, re.MULTILINE):
        print(f"{agents} 缺少完整的「速查 / 自动生成 / 常见坑」结构", file=sys.stderr)
        return 1
    else:
        separator = "\n" if content.endswith("\n") else "\n\n"
        updated = content + separator + f"{SECTION}\n\n{GENERATED}\n\n{generated}{MANUAL}\n"
    agents.write_text(updated, encoding="utf-8")
    print(f"{root.name} 速查已更新")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repo", nargs="?", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--assembly", type=Path, help="Framework 主线装配清单的路径")
    parser.add_argument("--check", action="store_true", help="速查有差异时退出 1")
    args = parser.parse_args()
    try:
        root = Path(git(args.repo, "rev-parse", "--show-toplevel").strip())
        common = Path(git(root, "rev-parse", "--path-format=absolute", "--git-common-dir").strip())
        original = common.parent
        assembly = args.assembly or original.parent / FRAMEWORK / "scripts/assembly.json"
        entry = next((repo for repo in read_json(assembly)["repositories"]
                      if repo["directory"] == original.name), None)
        if entry is None:
            raise ValueError(f"{original.name} 未登记在 {assembly}")
        generated = "\n".join(source_lines(root) + kz_lines(entry)) + "\n\n"
        return update(root, generated, args.check)
    except (OSError, subprocess.CalledProcessError, ValueError, KeyError) as error:
        print(f"生成速查失败：{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
