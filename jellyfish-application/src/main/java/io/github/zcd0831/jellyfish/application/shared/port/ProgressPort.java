package io.github.zcd0831.jellyfish.application.shared.port;

import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;

/**
 * 面向界面的进度推送；丢弃不影响业务。规则出处：模块-应用层 §6。
 */
public interface ProgressPort {

    void emit(ProgressEvent event);
}
