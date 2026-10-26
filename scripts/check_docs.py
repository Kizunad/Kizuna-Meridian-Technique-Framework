"""检查项目入口和 Wiki 的本地链接。"""

from pathlib import Path
import re
import sys


def main() -> int:
    # 只检查可维护文档，不扫描依赖或构建输出。
    root = Path(__file__).resolve().parents[1]
    pages = [root / "README.md", *sorted((root / "docs/wiki").glob("*.md"))]
    failures = []
    for page in pages:
        for target in re.findall(r"\[[^\]]+\]\(([^)]+)\)", page.read_text(encoding="utf-8")):
            if target.startswith(("https://", "http://", "#")):
                continue
            if not (page.parent / target.split("#", 1)[0]).is_file():
                failures.append(f"{page.relative_to(root)}: {target}")
    if failures:
        print("文档链接不存在：\n" + "\n".join(failures), file=sys.stderr)
        return 1
    print(f"文档检查通过：{len(pages)} 个页面。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
