package io.github.zcd0831.jellyfish.application.shared.port;

import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

/**
 * 一次状态转移的写入作用域；实现固定按「事实 → 追加 → 替换」执行并 fsync。规则出处：模块-应用层 §8.8。
 */
public interface CommitScope {

    /** 不可变事实：只新建，不覆盖。 */
    void stageFact(Object fact);

    /** 追加一条流水。 */
    void stageAppend(SessionId sessionId, Record record);

    /** 替换一个可变聚合（Session / Plan / Budget / RuntimePolicy 等）。 */
    void stageReplace(Object aggregate);

    /**
     * 全局资产写段（skills/、experience/）：并行子会话会同时沉淀，
     * 容量"读—判—写"必须在同一段内完成，否则两次 CREATE 会一起越过上限。
     * 规则出处：模块-应用层 §7.2、模块-基础设施层 §5。
     */
    void globalWrite(Runnable work);

    /** 按固定顺序落盘；失败即整个作用域失败，不得返回成功。 */
    void commit();
}
