package io.github.zcd0831.jellyfish.application.shared;

import java.util.concurrent.CancellationException;

/**
 * 取消信号：阻塞点（ask / join / 退避）必须能被它打断。
 * 规则出处：技术决策 D05（取消必须能打断阻塞）、D10（退避可取消）。
 */
public interface CancellationToken {

    boolean isCancelled();

    void onCancel(Runnable callback);

    void throwIfCancelled();
}
