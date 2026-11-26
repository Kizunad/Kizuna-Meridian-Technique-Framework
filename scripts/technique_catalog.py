"""读取正式 txtpb 目录，保留重复字段与跨文件的书卷关系。"""

import ast
import re
from collections import defaultdict
from dataclasses import dataclass, field


TOKEN = re.compile(r'''"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\#[^\n]*|[{}:]|[^\s{}:#"']+|["']''')


def parse(text):
    # 目录使用 txtpb 的标量和花括号消息子集；引号中的井号也是正文。
    tokens = iter(t for t in TOKEN.findall(text) if not t.startswith("#"))

    def message(nested):
        fields = defaultdict(list)
        for key in tokens:
            if key == "}" and nested:
                return fields
            if not key.isidentifier():
                raise ValueError(f"字段名无效：{key}")
            value = next(tokens, None)
            if value == ":":
                value = next(tokens, None)
            elif value != "{":
                raise ValueError(f"{key} 缺少冒号或左花括号")
            if value == "{":
                value = message(True)
            elif value is None or value in ("}", ":"):
                raise ValueError(f"{key} 缺值")
            elif value.startswith(('"', "'")):
                value = ast.literal_eval(value)
            fields[key].append(value)
        if nested:
            raise ValueError("消息缺少右花括号")
        return fields

    return message(False)


def one(fields, key, default=None):
    return fields.get(key, [default])[0]


def reference(entry):
    return one(entry, "id"), int(one(entry, "version"))


@dataclass
class Catalog:
    books: dict = field(default_factory=dict)
    volumes: dict = field(default_factory=dict)
    forms: dict = field(default_factory=dict)
    files: list = field(default_factory=list)

    def load(self, path):
        # 三类声明可以分在多个文件里，等所有文件读完再解引用。
        try:
            data = parse(path.read_text(encoding="utf-8"))
            for key, index in (("definitions", self.forms), ("books", self.books), ("volumes", self.volumes)):
                for entry in data.get(key, []):
                    ident = reference(entry) if key == "definitions" else one(entry, "id")
                    if ident in index:
                        raise ValueError(f"重复声明 {ident}")
                    index[ident] = entry
            if data.keys() & {"definitions", "books", "volumes"}:
                self.files.append(path)
        except (ValueError, SyntaxError, TypeError) as error:
            raise ValueError(f"{path}: {error}") from error


def read_catalog(root):
    # 只在正式源码的 data 根展开命名空间。提前剪枝，避免误收构建镜像和交接备份。
    catalog = Catalog()
    for directory, children, _ in root.walk():
        if directory.name == "src":
            for path in sorted(directory.glob("main/resources/data/*/*.txtpb")):
                catalog.load(path)
            children.clear()
            continue
        children[:] = sorted(p for p in children if not p.startswith((".", "wt-"))
                             and p.lower() not in {"build", "out", "run", "node_modules", "handoff", "logs",
                                                   "tests", "test", "gametest", "clienttest", "verification"})
    if not catalog.forms:
        raise ValueError(f"{root} 没有正式功法定义")
    return catalog
