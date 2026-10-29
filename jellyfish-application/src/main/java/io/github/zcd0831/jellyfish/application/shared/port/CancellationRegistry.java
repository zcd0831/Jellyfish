package io.github.zcd0831.jellyfish.application.shared.port;

import io.github.zcd0831.jellyfish.application.shared.CancellationToken;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

/**
 * 按会话取终止信号：模型调用与工具执行据此判断“本轮是否已被终止”。
 * 端口而非具体类，是因为基础设施（传输层、本地工具）也要读同一个信号。
 * 规则出处：08-application §8.9.4.1、09-infrastructure §9.11。
 */
public interface CancellationRegistry {

    /** 该会话当前的终止信号；没有在跑的 run 时返回「永不取消」。 */
    CancellationToken forSession(SessionId sessionId);
}
