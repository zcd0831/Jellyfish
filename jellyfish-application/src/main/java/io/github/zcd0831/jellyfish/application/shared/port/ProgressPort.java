package io.github.zcd0831.jellyfish.application.shared.port;

import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;

/**
 * 面向界面的进度推送；丢弃不影响业务。规则出处：08-application §8.7。
 */
public interface ProgressPort {

    void emit(ProgressEvent event);
}
