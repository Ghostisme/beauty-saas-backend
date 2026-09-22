package com.beauty.saas.order;

import com.beauty.saas.common.ApiException;
import java.util.List;
import java.util.Set;

public final class OrderCatalog {
    private OrderCatalog() {}
    public record Option(String value,String label) {}
    public static final List<Option> CONSUMPTION_TYPES=List.of(
        new Option("SERVICE","项目"),new Option("PRODUCT","产品"),new Option("OPEN_CARD","开卡"),
        new Option("CARD_RECHARGE","卡充值"),new Option("CARD_EXCHANGE","卡换卡"),new Option("ORDER_REFUND","退单"),
        new Option("CARD_REFUND","退卡"),new Option("CROSS_STORE","跨店消费"),new Option("SUPPLEMENT","补录单"),
        new Option("MEITUAN_REDEEM","美团团购核销"),new Option("CAMPAIGN_REDEEM","营销活动核销"),
        new Option("ADDITIONAL_PAYMENT","补款单"),new Option("DOUYIN_REDEEM","抖音团购核销"),
        new Option("BALANCE_SETTLEMENT","尾款还清"),new Option("PARTIAL_REFUND","退部分单")
    );
    public static final Set<String> PAYMENT_CATEGORIES=Set.of("RECEIPT","CARD_CONSUMPTION","OTHER");
    // Categories are recorded on payment snapshots by the order producer, not guessed from method names.
    public static final List<Option> PAYMENT_FILTERS=List.of(
        new Option("RECEIPT","实收类"),new Option("CARD_CONSUMPTION","卡耗类"),new Option("OTHER","非实收非卡耗类"),
        new Option("CARD_BUY_CARD","卡买卡"),new Option("DEBT","欠款"),new Option("WALLET","钱包"),
        new Option("ALIPAY","支付宝"),new Option("POS","POS"),new Option("SHOUQIANBA","收钱吧"),
        new Option("GIFT","赠送"),new Option("CAMPAIGN","营销活动"),new Option("POINTS","积分抵现"),
        new Option("MEMBER_BENEFIT","通用会员权益"),new Option("DOUYIN","抖音团购"),new Option("MEITUAN","美团团购"),
        new Option("CASH","现金"),new Option("CARD_GIFT_BENEFIT","卡赠送权益"),new Option("WAIVER","减免"),
        new Option("MEMBERSHIP_CARD","会员卡"),new Option("GIFT_REDEEM","赠品核销"),new Option("GIFT_CREDIT","赠送消费金"),
        new Option("WECHAT","微信"),new Option("COUPON","券核销"),new Option("CONTENT_REDEEM","营销内容核销")
    );
    public static void validate(List<Option> options,String value,String label) {
        if (value!=null && !value.isBlank() && options.stream().noneMatch(option -> option.value().equals(value)))
            throw new ApiException(400,label+"不正确");
    }
}
