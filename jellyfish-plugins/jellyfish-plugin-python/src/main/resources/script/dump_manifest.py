"""清单生成器：把脚本的装饰器声明导出成 ``manifest.json``。

脚本的能力对宿主而言**只有** ``manifest.json`` 一个来源，而实现的事实写在装饰器里，
两者必须一致。手工维护两份声明迟早会漂移，而漂移的表现是「脚本拒绝服务」——
甚至更糟，是「模型按一份不存在的工具定义去调用」。这个工具就是用来消除这份手写工作的：

    # 打印生成结果
    python3 dump_manifest.py scripts/python/jira

    # 检查仓库里的 manifest.json 是否跟得上实现（不一致退 1，并打印差异）
    python3 dump_manifest.py scripts/python/jira --check

    # 生成时把 id 也写进去（桥接插件不读这个字段，它认的是目录名）
    python3 dump_manifest.py scripts/python/jira --id jira

它的输出只包含清单需要的字段：``handler`` 这类只有运行期才有意义的东西不进清单，
否则清单结构就不再是「协议定义的一份数据」。

**为什么要独立成文件而不是塞进网关**：清单生成是**离线**动作（开发期用），
它要求进程里只能有一个脚本被导入；而网关是运行期进程，同时管着多个脚本。
两者共用一个入口，迟早会有人拿一个半配好的网关目录去生成清单。
"""

import argparse
import importlib.util
import json
import os
import sys

# 与 gateway.py 同一个资源目录，因此这里直接导入即可
import jellyfish_sdk


def _load_entry(script_dir, entry):
    """在 ``script_dir`` 下按 ``entry`` 加载脚本模块。

    :param script_dir: 脚本目录
    :param entry: 入口文件名（相对脚本目录）
    :return: 已加载的模块
    """
    path = os.path.join(script_dir, entry)
    if not os.path.isfile(path):
        raise SystemExit("入口文件不存在: %s" % path)
    # 脚本目录必须在 sys.path 最前面：脚本往往有同目录的兄弟模块
    sys.path.insert(0, script_dir)
    # 用「按文件路径加载」而不是 import：脚本模块名可能与本进程里的东西重名
    # （比如它就叫 main），按名字导入会静默拿到别人的模块
    spec = importlib.util.spec_from_file_location("jellyfish_script_under_edit", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _entry_name(script_dir):
    """读脚本目录里已有清单声明的入口名；没有清单时按 ``main.py``。

    **空文件按「没有清单」处理**，这不是宽容而是必需：
    ``python3 dump_manifest.py dir > dir/manifest.json`` 这个很自然的写法会让 shell
    在脚本运行之前就把目标文件截断成 0 字节，于是「读现有清单」读到的正是自己即将覆盖的
    空文件。按合法性硬失败的话，用户会拿到一句「不是合法 JSON」，而原因与他的操作看起来
    毫无关系。``--write`` 能绕开这个陷阱，但没理由让不用它的人踩坑。

    :param script_dir: 脚本目录
    :return: 入口文件名
    """
    manifest_path = os.path.join(script_dir, "manifest.json")
    if not os.path.isfile(manifest_path):
        return "main.py"
    with open(manifest_path, "r", encoding="utf-8") as handle:
        text = handle.read().strip()
    if not text:
        return "main.py"
    try:
        declared = json.loads(text)
    except ValueError as error:
        raise SystemExit("现有的 manifest.json 不是合法 JSON: %s" % error)
    return declared.get("entry") or "main.py"


def _normalize_tool(tool):
    """把工具声明补齐成「省略即缺省」的统一形状，供比较使用。

    :param tool: 工具声明映射
    :return: 补齐后的映射
    """
    return {
        "name": tool.get("name"),
        "description": tool.get("description") or "",
        "parameters": tool.get("parameters") or {},
        "required": tool.get("required") or [],
        "readOnly": bool(tool.get("readOnly")),
    }


def _normalize(manifest):
    """把一份清单补齐成可比对的统一形状。

    只做「缺省值补全」与「集合排序」，不丢弃任何会被内核读到的字段：
    描述与参数也要比，因为它们决定模型看到的工具定义——漂移一次，
    模型就会按一个不存在的签名去调用。

    :param manifest: 清单映射
    :return: 补齐后的映射
    """
    normalized = {"entry": manifest.get("entry") or "main.py"}
    if manifest.get("id"):
        normalized["id"] = manifest["id"]
    normalized["tools"] = [_normalize_tool(tool) for tool in manifest.get("tools") or []]
    normalized["commands"] = [
        {
            "name": command.get("name"),
            "descriptor": {
                "summary": (command.get("descriptor") or {}).get("summary"),
                "usage": (command.get("descriptor") or {}).get("usage"),
                "aliases": (command.get("descriptor") or {}).get("aliases") or [],
            },
            "hasOptions": bool(command.get("hasOptions")),
        }
        for command in manifest.get("commands") or []
    ]
    normalized["commandOptions"] = sorted(
        option.get("name") for option in manifest.get("commandOptions") or [])
    normalized["contributions"] = sorted(manifest.get("contributions") or [])
    normalized["events"] = sorted(manifest.get("events") or [])
    return normalized


def _names_of(items):
    """取列表里每一项的名字；不是「每一项都带 name」时返回 ``None``。

    一份清单里的列表绝大多数是「带名字的条目」（工具、命令、候选查询），
    能取到名字就可以按名字比对——而那正是用户唯一想知道的事：
    「到底是哪一个工具对不上」。

    :param items: 列表
    :return: 名字列表；取不出时返回 ``None``
    """
    names = []
    for item in items:
        if not isinstance(item, dict) or not isinstance(item.get("name"), str):
            return None
        names.append(item["name"])
    return names


def _differences(expected, actual, prefix=""):
    """逐层找出两份清单的差异。

    :param expected: 从装饰器生成的那一份
    :param actual: 磁盘上那一份（已补齐）
    :param prefix: 当前路径前缀
    :return: 差异描述列表
    """
    found = []
    if isinstance(expected, dict) and isinstance(actual, dict):
        for key in sorted(set(list(expected.keys()) + list(actual.keys()))):
            path = "%s.%s" % (prefix, key) if prefix else key
            if key not in expected:
                found.append("%s: 清单里多出了这一项（实现没有）" % path)
            elif key not in actual:
                found.append("%s: 实现里有，清单里没有" % path)
            else:
                found.extend(_differences(expected[key], actual[key], path))
        return found
    if isinstance(expected, list) and isinstance(actual, list):
        names_in_expected = _names_of(expected)
        names_in_actual = _names_of(actual)
        if names_in_expected is not None and names_in_actual is not None:
            # 按名字比而不是按下标：清单里插入一个条目会让后面所有下标错位，
            # 按下标报出来的是「第 2 个工具的名字不对」这种没用的结论
            missing = [name for name in names_in_expected if name not in names_in_actual]
            extra = [name for name in names_in_actual if name not in names_in_expected]
            if missing:
                found.append("%s: 实现里有、清单里没有: %s" % (prefix, "、".join(missing)))
            if extra:
                found.append("%s: 清单里多出了这一项（实现没有）: %s" % (prefix, "、".join(extra)))
            by_name_expected = {}
            by_name_actual = {}
            for item in expected:
                by_name_expected.setdefault(item["name"], item)
            for item in actual:
                by_name_actual.setdefault(item["name"], item)
            # 两边都有的名字逐项比内容：即使上面刚报了「多了一个」也照比，
            # 因为「多了一个条目」与「另一个条目的描述忘了改」常常是同一轮改动造成的，
            # 一次报全比让用户改一处再跑一次便宜
            for name in sorted(set(by_name_expected) & set(by_name_actual)):
                found.extend(_differences(by_name_expected[name], by_name_actual[name],
                                          "%s[%s]" % (prefix, name)))
            for name in sorted(set(names_in_expected) | set(names_in_actual)):
                # 上面已经报告过「多出 / 缺失」的名字不再重复一遍次数
                if name in missing or name in extra:
                    continue
                if names_in_expected.count(name) != names_in_actual.count(name):
                    found.append("%s: %s 出现次数不同（实现 %d，清单 %d）"
                                 % (prefix, name, names_in_expected.count(name),
                                    names_in_actual.count(name)))
            return found
        if len(expected) != len(actual):
            found.append("%s: 条目数不同（实现 %d，清单 %d）: %s / %s"
                         % (prefix, len(expected), len(actual),
                            _brief(expected), _brief(actual)))
            return found
        for index, (left, right) in enumerate(zip(expected, actual)):
            found.extend(_differences(left, right, "%s[%d]" % (prefix, index)))
        return found
    if expected != actual:
        found.append("%s: 实现是 %s，清单是 %s" % (prefix, _brief(expected), _brief(actual)))
    return found


def _brief(value):
    """把值压成一行短文本，供差异输出使用。

    :param value: 任意值
    :return: 文本
    """
    text = json.dumps(value, ensure_ascii=False, sort_keys=True)
    return text if len(text) <= 60 else text[:57] + "..."


def main(argv=None):
    """命令行入口。

    :param argv: 参数列表；``None`` 表示取进程参数
    :return: 进程退出码
    """
    parser = argparse.ArgumentParser(
        prog="dump_manifest.py", description="从装饰器声明导出脚本清单")
    parser.add_argument("script_dir", help="脚本目录（其下有入口文件与 manifest.json）")
    parser.add_argument("--id", dest="script_id", default=None, help="把脚本标识也写进清单")
    parser.add_argument("--check", action="store_true",
                        help="与目录里现有的 manifest.json 比对，不一致时退 1")
    parser.add_argument("--entry", dest="entry", default=None, help="入口文件名，缺省读清单或 main.py")
    parser.add_argument("--write", action="store_true",
                        help="直接写入目录里的 manifest.json（推荐，绕开 shell 重定向先把文件截空）")
    options = parser.parse_args(argv)

    script_dir = os.path.abspath(options.script_dir)
    if not os.path.isdir(script_dir):
        raise SystemExit("脚本目录不存在: %s" % script_dir)
    entry = options.entry or _entry_name(script_dir)
    _load_entry(script_dir, entry)
    generated = jellyfish_sdk.dump_manifest(options.script_id, entry)
    # 打印的是**内核认得的清单原文**，可以原样落盘；用于比对的是补齐缺省值之后的形状。
    # 两者必须是两份数据：打印一份「为比较而改过的」形状，用户抄回去就会得到一份
    # 内核不认的清单（例如 commandOptions 变成了字符串数组）
    text = json.dumps(generated, ensure_ascii=False, indent=2)

    manifest_path = os.path.join(script_dir, "manifest.json")
    if options.write and not options.check:
        with open(manifest_path, "w", encoding="utf-8") as handle:
            handle.write(text + "\n")
        print("已写入清单: %s" % manifest_path)
        return 0
    if not options.check:
        print(text)
        return 0

    if not os.path.isfile(manifest_path):
        print("清单不存在: %s\n生成结果如下，可直接落盘：\n%s" % (manifest_path, text),
              file=sys.stderr)
        return 1
    with open(manifest_path, "r", encoding="utf-8") as handle:
        actual = _normalize(json.load(handle))
    found = _differences(_normalize(generated), actual)
    if not found:
        print("清单与实现一致: %s" % manifest_path)
        return 0
    print("清单与实现不一致: %s" % manifest_path, file=sys.stderr)
    for line in found:
        print("  " + line, file=sys.stderr)
    print("\n实现里的声明（把它抄进清单，或直接采用）:\n%s" % text, file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
