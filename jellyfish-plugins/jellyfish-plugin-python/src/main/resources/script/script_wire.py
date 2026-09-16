# -*- coding: utf-8 -*-
"""网关与 worker 两侧共用的分帧约定。

两侧都在同一条管道/套接字上收发，帧格式必须由同一份代码定义——各写一份的后果是
「宿主能发、worker 读不出」，而这类问题的现场表现是「脚本毫无反应」，
排查时既看不到异常也看不到错误码。

帧格式与宿主侧完全一致：**一行一个 UTF-8 JSON 对象**。用行分隔而不是长度前缀，
是因为它允许「错一行、丢一行、继续」；长度前缀会让任何一次误打印把后续所有帧错位，
而恢复无从下手。
"""

import json

# 单帧上限：与宿主侧的传输上限一致。超限说明脚本返回了不该返回的大对象，
# 早失败好过把内存吃满。
MAX_FRAME_BYTES = 10 * 1024 * 1024


def encode(message):
    """把一个对象编码成一帧字节（含结尾换行）。"""
    return (json.dumps(message, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")


def feed(buffer, chunk):
    """把新到的字节并入缓冲区并切出完整的帧。

    返回 ``(剩余缓冲, [帧, ...])``。无法解析的行（脚本误打印、解释器警告）直接丢弃：
    它不该让一次调用失败，也不该被计入任何失败账目。
    """
    buffer += chunk
    frames = []
    while True:
        index = buffer.find(b"\n")
        if index < 0:
            break
        line = buffer[:index]
        buffer = buffer[index + 1:]
        if not line.strip():
            continue
        try:
            frames.append(json.loads(line.decode("utf-8")))
        except (ValueError, UnicodeDecodeError):
            continue
    return buffer, frames


def take_lines(buffer, chunk):
    """把新到的字节并入缓冲区并按行切分文本（用于把子进程 stderr 转进日志）。

    与 :func:`feed` 的区别只在用途：日志是给人看的，因此不要求是 JSON，
    但**必须能把多字节字符完整还原**——脚本里的中文日志恰恰是最需要看清的部分。
    """
    buffer += chunk
    lines = []
    while True:
        index = buffer.find(b"\n")
        if index < 0:
            break
        line = buffer[:index]
        buffer = buffer[index + 1:]
        lines.append(line.decode("utf-8", "replace").rstrip("\r"))
    return buffer, lines
