package com.github.guoyyit.tenantmeteringbillingjava.model;

/**
 * 超出迟到时间窗的调用记录归属策略。
 */
public enum LateArrivalPolicy {
    /** 拒绝记录 */
    REJECT,
    /** 归入当前账期 */
    CURRENT_PERIOD,
    /** 归入最近一个仍可写入的账期 */
    NEAREST_WRITABLE
}
