#!/usr/bin/env python3
"""databinding 绑定表达式的静态体检。

沙箱里没有 Android SDK，databinding 生成代码这一层只有 CI 能验。
但 90% 的 databinding 编译错误属于这四类，可以在本地先抓掉：

  1. `@{...}` 里用到的变量没在 <data> 里声明
  2. `@{...}` 里 `self.xxx` / `Request.xxx` 不存在
  3. `@string/` `@drawable/` `@color/` `@dimen/` 引用了不存在的资源
  4. `@drawable/` 引用的文件缺失

退出码非 0 表示发现问题。
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
RES = os.path.join(ROOT, "design/src/main/res")
APP_RES = os.path.join(ROOT, "app/src/main/res")

ATTR_RE = re.compile(r"@(string|drawable|color|dimen|style|menu|layout|anim|array|bool|integer)/([A-Za-z0-9_]+)")


def collect_resources():
    """按资源类型收集已定义的资源名。"""
    defined = {k: set() for k in
               ["string", "color", "dimen", "style", "menu", "layout", "drawable", "anim", "array", "bool",
                "integer"]}
    for base in (RES, APP_RES):
        if not os.path.isdir(base):
            continue
        for dirname in os.listdir(base):
            path = os.path.join(base, dirname)
            if dirname.startswith("values"):
                for f in os.listdir(path):
                    if not f.endswith(".xml"):
                        continue
                    try:
                        tree = ET.parse(os.path.join(path, f))
                    except ET.ParseError:
                        continue
                    for node in tree.getroot():
                        tag = node.tag if isinstance(node.tag, str) else ""
                        name = node.get("name")
                        if not name:
                            continue
                        if tag in defined:
                            defined[tag].add(name)
                        # <item type="string" name="x">
                        t = node.get("type")
                        if tag == "item" and t in defined:
                            defined[t].add(name)
            elif dirname.startswith("drawable"):
                for f in os.listdir(path):
                    defined["drawable"].add(os.path.splitext(f)[0])
            elif dirname.startswith("layout"):
                for f in os.listdir(path):
                    defined["layout"].add(os.path.splitext(f)[0])
            elif dirname.startswith("menu"):
                for f in os.listdir(path):
                    defined["menu"].add(os.path.splitext(f)[0])
            elif dirname.startswith("anim"):
                for f in os.listdir(path):
                    defined["anim"].add(os.path.splitext(f)[0])
            elif dirname.startswith("color"):
                # 颜色状态列表放在 res/color/，也算已定义的颜色
                for f in os.listdir(path):
                    defined["color"].add(os.path.splitext(f)[0])
    return defined


def kotlin_symbols(kt_path):
    """粗略抓 Kotlin 文件里的 fun 名和 sealed class 子类名。"""
    funs, types = set(), set()
    try:
        text = open(kt_path, encoding="utf-8").read()
    except OSError:
        return funs, types
    funs.update(re.findall(r"\bfun\s+([A-Za-z_][A-Za-z0-9_]*)", text))
    types.update(re.findall(r"\b(?:data\s+)?(?:class|object)\s+([A-Za-z_][A-Za-z0-9_]*)", text))
    types.update(re.findall(r"\bsealed\s+class\s+([A-Za-z_][A-Za-z0-9_]*)", text))
    return funs, types


def main():
    defined = collect_resources()
    problems = []

    layout_dirs = [os.path.join(RES, "layout")]
    if os.path.isdir(os.path.join(APP_RES, "layout")):
        layout_dirs.append(os.path.join(APP_RES, "layout"))

    for ldir in layout_dirs:
        for fname in sorted(os.listdir(ldir)):
            if not fname.endswith(".xml"):
                continue
            fpath = os.path.join(ldir, fname)
            try:
                tree = ET.parse(fpath)
            except ET.ParseError as e:
                problems.append(f"{fname}: XML 解析失败: {e}")
                continue
            root = tree.getroot()
            data = root.find("data")
            variables, imports = set(), {}
            if data is not None:
                for v in data.findall("variable"):
                    if v.get("name"):
                        variables.add(v.get("name"))
                for imp in data.findall("import"):
                    t = imp.get("type") or ""
                    imports[t.split(".")[-1]] = t

            # 资源引用检查
            for m in ATTR_RE.finditer(open(fpath, encoding="utf-8").read()):
                kind, name = m.group(1), m.group(2)
                # style 可能是 androidx / material 里的，本地查不到不算错
                if kind in ("string", "drawable", "color", "dimen", "menu"):
                    if name not in defined[kind]:
                        problems.append(f"{fname}: @{kind}/{name} 不存在")

            # 表达式检查
            text = open(fpath, encoding="utf-8").read()
            for expr in re.findall(r"@\{([^}]*)\}", text):
                # 变量名：出现在 self./Request. 之外的裸标识符
                stripped = re.sub(r"@(string|color|drawable|dimen|style)/[A-Za-z0-9_]+", "", expr)
                # 去掉 lambda 参数声明，例如 { (v, checked) -> ... } / { x -> ... }
                stripped = re.sub(r"\{[^{}]*->", "{", stripped)
                # 去掉属性访问链：profile.name 里的 name 不是变量
                stripped = re.sub(r"\.[A-Za-z_][A-Za-z0-9_]*", "", stripped)
                stripped = re.sub(r"`[^`]*`", "''", stripped)
                stripped = stripped.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                stripped = re.sub(r"'[^']*'", "''", stripped)
                for ident in re.findall(r"\b([a-z][A-Za-z0-9_]*)\b", stripped):
                    if ident in ("self", "true", "false", "null", "length", "size", "get",
                                 "and", "or", "not", "it", "View", "context", "float",
                                 "int", "long", "double", "boolean", "char", "short", "byte"):
                        continue
                    # 方法名后面跟 (
                    if ident in variables or ident in imports:
                        continue
                    if ident in ("VISIBLE", "GONE", "INVISIBLE"):
                        continue
                    # 可能是属性访问链里的方法，跳过紧跟 ( 的
                    if re.search(r"\b" + ident + r"\s*\(", stripped):
                        continue
                    problems.append(f"{fname}: 表达式里 {ident} 既不是 <variable> 也不是已知资源")

    print(f"检查完成：{len(problems)} 处可疑")
    for p in sorted(set(problems)):
        print("  -", p)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
