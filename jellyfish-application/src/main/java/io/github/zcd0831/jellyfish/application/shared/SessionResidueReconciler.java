package io.github.zcd0831.jellyfish.application.shared;

import io.github.zcd0831.jellyfish.domain.perception.session.Session;

/**
 * 续跑前的对账入口：只补齐**这一个会话**的崩溃残留。
 * <p>
 * 单独抽成接口，是因为用例只需要"跑之前先补一下"这一个动作，
 * 不需要整个对账器背后的六个仓储。
 * 规则出处：08-application §8.10、§8.9.3（`U3->>SR: reconcile(session)`）。
 */
public interface SessionResidueReconciler {

    void reconcileBeforeRun(Session session);
}
