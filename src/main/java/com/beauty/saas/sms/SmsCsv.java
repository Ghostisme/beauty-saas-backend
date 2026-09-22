package com.beauty.saas.sms;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import static com.beauty.saas.iam.IamRepository.text;

/** RFC 4180 quoting plus spreadsheet formula neutralization for all untrusted text cells. */
public final class SmsCsv {
    private SmsCsv() {}
    public static String cell(Object input) {
        String value=input==null?"":input instanceof LocalDateTime time?time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")):input.toString();
        String stripped=value.stripLeading();
        if ((!stripped.isEmpty() && "=+-@\uFF1D\uFF0B\uFF0D\uFF20".indexOf(stripped.charAt(0))>=0) || value.startsWith("\t") || value.startsWith("\r") || value.startsWith("\n")) value="'"+value;
        return "\""+value.replace("\"","\"\"")+"\"";
    }
    public static String status(String code) {
        return switch (code) { case "PENDING" -> "待发送"; case "SUBMITTED" -> "已提交服务商"; case "DELIVERED" -> "送达成功"; case "FAILED" -> "发送失败"; default -> "状态未知"; };
    }
    public static byte[] render(List<Map<String,Object>> rows) {
        StringBuilder csv=new StringBuilder("\uFEFF所属企业,企业编码,手机号,短信类型,短信内容,发送时间,发送状态,计费条数,失败原因\r\n");
        for (var row:rows) {
            var values=List.of(text(row,"tenantName"),text(row,"tenantCode"),text(row,"phone"),SmsCatalog.name(text(row,"templateCode")),text(row,"content"),row.get("sendTime"),status(text(row,"status")),row.get("billedUnits"),text(row,"failureReason"));
            csv.append(String.join(",",values.stream().map(SmsCsv::cell).toList())).append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }
}
