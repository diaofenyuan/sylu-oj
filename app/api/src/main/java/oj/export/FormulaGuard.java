package oj.export;

/**
 * 公式注入防护：用户可控单元格（学号、姓名、题名等）若以
 * = + - @ 或 Tab/回车开头，前置单引号使其在 Excel/WPS 中按文本处理。
 * CSV 与 XLSX 共用该策略。
 */
public final class FormulaGuard {

    private FormulaGuard() {
    }

    public static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r') {
            return "'" + value;
        }
        return value;
    }
}
