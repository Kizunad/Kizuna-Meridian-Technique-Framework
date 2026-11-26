"""检查项目入口和 Wiki 的本地链接及手册引用的仓库路径。"""

from pathlib import Path
import re
import sys


COOKBOOK = "Humanoid-Technique-Cookbook.md"
SOURCE_LINK = re.compile(r"^https://github\.com/Kizunad/([^/]+)/blob/[^/]+/(.+)$")


def local_target(root: Path, page: Path, target: str) -> Path | None:
    path = target.split("#", 1)[0]
    if not path:
        return None
    if page.name == COOKBOOK and (source := SOURCE_LINK.fullmatch(path)):
        repo, relative = source.groups()
        return root.parent / repo / relative
    if path.startswith(("https://", "http://")):
        return None
    return page.parent / path


def main() -> int:
    # 只检查可维护文档，不扫描依赖或构建输出。
    root = Path(__file__).resolve().parents[1]
    pages = [root / "README.md", *sorted((root / "docs/wiki").glob("*.md"))]
    failures = []
    references = 0
    for page in pages:
        for number, line in enumerate(page.read_text(encoding="utf-8").splitlines(), 1):
            for target in re.findall(r"\[[^\]]+\]\(([^)]+)\)", line):
                path = local_target(root, page, target)
                if path is None:
                    continue
                references += 1
                if not path.is_file():
                    failures.append(f"{page.relative_to(root)}:{number}: {target}")
    if failures:
        print("文档链接不存在：\n" + "\n".join(failures), file=sys.stderr)
        return 1
    print(f"文档检查通过：{len(pages)} 个页面，{references} 条路径。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
