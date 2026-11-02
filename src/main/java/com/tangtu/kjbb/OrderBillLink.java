package com.tangtu.kjbb;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提单 ↔ 订单 关联表（内存态）
 *
 * 用途：卖家发货时为买家生成一张提单，并记下这张提单对应哪个订单；
 *      包裹真正到达买家提货点后，凭这个关联把提单核销掉。
 *
 * 重启后关联丢失也不会出错：核销时找不到关联就跳过（提单留在玩家手里）。
 */
public final class OrderBillLink {

    private static final Map<Long, Long> SERIAL_TO_ORDER = new LinkedHashMap<>();

    private OrderBillLink() {
    }

    public static synchronized void put(long serial, long orderId) {
        SERIAL_TO_ORDER.put(serial, orderId);
    }

    public static synchronized Long orderOf(long serial) {
        return SERIAL_TO_ORDER.get(serial);
    }

    public static synchronized void removeSerial(long serial) {
        SERIAL_TO_ORDER.remove(serial);
    }

    public static synchronized void removeOrder(long orderId) {
        SERIAL_TO_ORDER.entrySet().removeIf(e -> e.getValue() != null && e.getValue() == orderId);
    }

    /** 找出这个订单对应的提单序列号（没有则 -1） */
    public static synchronized long serialOfOrder(long orderId) {
        for (Map.Entry<Long, Long> e : SERIAL_TO_ORDER.entrySet()) {
            if (e.getValue() != null && e.getValue() == orderId) {
                return e.getKey();
            }
        }
        return -1L;
    }
}
