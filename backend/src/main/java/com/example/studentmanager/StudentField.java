package com.example.studentmanager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

public record StudentField(String key, String label, String type, boolean required) {
    public StudentField { if (genderLabel(label)) required = true; }
    public boolean gender() { return genderLabel(label); }
    public static boolean genderLabel(String label) { return label != null && java.util.Set.of("性别", "gender", "sex").contains(java.text.Normalizer.normalize(label, java.text.Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT)); }
    @com.fasterxml.jackson.annotation.JsonProperty("options")
    public java.util.List<String> options() { return gender() ? java.util.List.of("男", "女") : java.util.List.of(); }
    public static String type(String input) {
        String type = input == null ? "TEXT" : input.strip().toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("TEXT", "INTEGER", "DECIMAL", "DATE", "BOOLEAN").contains(type)) throw AppException.bad("不支持的字段类型");
        return type;
    }
    public static String label(String input) {
        if (input == null || input.strip().isEmpty()) throw AppException.bad("字段名称不能为空");
        String value = input.strip();
        if (value.length() > 40 || value.codePoints().anyMatch(Character::isISOControl)) throw AppException.bad("字段名称最多40个字符，不能包含控制字符");
        return value;
    }
    public String sqlType() { if (gender()) { if (!type.equals("TEXT")) throw AppException.bad("性别字段必须使用文本类型，值仅限男或女"); return "VARCHAR(1)"; } return switch (type) { case "INTEGER" -> "BIGINT"; case "DECIMAL" -> "DECIMAL(20,6)"; case "DATE" -> "DATE"; case "BOOLEAN" -> "BOOLEAN"; default -> "VARCHAR(255)"; }; }
    public Object normalize(Object raw) {
        if (raw == null || raw.toString().strip().isEmpty()) { if (required) throw AppException.bad(label + "不能为空"); return null; }
        String value = raw.toString().strip();
        if (gender()) { if (!type.equals("TEXT") || !options().contains(value)) throw AppException.bad("性别只能为男或女"); return value; }
        try {
            return switch (type) {
                case "INTEGER" -> Long.toString(new BigDecimal(value).longValueExact());
                case "DECIMAL" -> { BigDecimal number = new BigDecimal(value).stripTrailingZeros(); if (number.precision() - number.scale() > 14 || Math.max(0, number.scale()) > 6) throw new IllegalArgumentException(); yield number.toPlainString(); }
                case "DATE" -> LocalDate.parse(value).toString();
                case "BOOLEAN" -> { if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) throw new IllegalArgumentException(); yield Boolean.parseBoolean(value); }
                default -> { if (value.length() > 255 || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(); yield value; }
            };
        } catch (Exception ex) { throw AppException.bad(label + "格式不正确：" + switch (type) { case "INTEGER" -> "请填写64位整数"; case "DECIMAL" -> "最多14位整数和6位小数"; case "DATE" -> "日期格式为yyyy-MM-dd"; case "BOOLEAN" -> "请选择是或否"; default -> "文本最多255字，不能包含控制字符"; }); }
    }
}
