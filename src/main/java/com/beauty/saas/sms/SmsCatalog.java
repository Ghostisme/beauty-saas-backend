package com.beauty.saas.sms;

import com.beauty.saas.common.ApiException;
import java.util.List;

/** System notification copy, not approved provider templates. Variables remain literal in the UI. */
public final class SmsCatalog {
    private SmsCatalog() {}
    public record Template(String code,String name,String content,String configKind) {}
    public record Group(String key,String title,List<Template> templates) {}
    private static Template item(String code,String name,String content) { return new Template(code,name,content,"NONE"); }
    public static final List<Group> GROUPS=List.of(
        new Group("transaction","交易提醒",List.of(
            item("CARD_RECHARGE","储值卡-充值","亲爱的顾客，感谢您光临${shop_name}，您已成功充值${money1}，当前会员资产${card_name}剩余：${money2}，详情可至会员中心查看。祝您生活愉快，欢迎下次光临~"),
            item("CARD_EXCHANGE","更换会员卡","亲爱的顾客，您已成功更换${card_name1}为${card_name2}，本次消费${money1}，当前会员资产${card_name3}剩余：${money2}，详情可至会员中心查看。祝您生活愉快，欢迎下次光临~"),
            item("CARD_OPEN","开卡成功","亲爱的顾客，感谢您光临${shop_name}，您已成功办理会员卡${card_name}，详情可至会员中心查看。祝您生活愉快，欢迎下次光临~"),
            item("OFFLINE_PAYMENT","线下支付方式支付","亲爱的顾客，感谢您光临${shop_name}，您本次消费${money}，详情可至会员中心查看。祝您生活愉快，欢迎下次光临~"),
            item("MIXED_PAYMENT","多种卡和线下支付方式支付","亲爱的顾客，感谢您光临${shop_name}，您本次消费${consume_info}，当前会员资产${member_asset}，详情可至会员中心查看。祝您生活愉快，欢迎下次光临~")
        )),
        new Group("appointment","预约提醒",List.of(
            item("APPOINTMENT_CREATED","预约成功","亲爱的顾客，您已成功预约${shop_name}，时间为${time}，请您按照预约时间前往${address}，期待您的光临~"),
            item("APPOINTMENT_CHANGED","预约时间修改","亲爱的顾客，您在${shop_name}的预约时间已变更为${time}，请您按照最新时间前往${address}，期待您的光临~"),
            item("APPOINTMENT_CANCELLED","取消预约","亲爱的顾客，已成功取消您在${shop_name}原定于${time}的预约，可重新预约。期待您的下次光临~"),
            new Template("APPOINTMENT_REMINDER","预约提醒","亲爱的顾客，您在${shop_name}的预约${time}即将开始，地址为${address}，期待您的到访~","APPOINTMENT")
        )),
        new Group("special","特殊日期提醒",List.of(
            new Template("BIRTHDAY_GREETING","生日祝福","亲爱的顾客${user_nick}，您的生日将至，${shop_name}祝您生日快乐！${usage_info}到店可享受${usage_info1}，请提前致电预约，详情请咨询门店~","BIRTHDAY")
        )),
        new Group("partner","合伙人相关",List.of(
            item("PARTNER_PAYOUT_SUCCEEDED","提现打款成功","尊敬的消费股东您好，您的提现申请已打款，请及时查看！"),
            item("PARTNER_PAYOUT_FAILED","提现打款失败","尊敬的消费股东您好，您的提现申请打款失败，原因为：${usage_info}；请重新修改后提交申请！"),
            item("COUPON_RECEIVED","赠券到账","恭喜您成功获得${ticket_name}一张，详情请咨询门店。祝您生活愉快！")
        ))
    );
    public static Template template(String code) {
        return GROUPS.stream().flatMap(group -> group.templates().stream()).filter(item -> item.code().equals(code)).findFirst().orElseThrow(() -> new ApiException(404,"短信模板不存在"));
    }
    public static String name(String code) { return GROUPS.stream().flatMap(group -> group.templates().stream()).filter(item -> item.code().equals(code)).map(Template::name).findFirst().orElse(code); }
}
