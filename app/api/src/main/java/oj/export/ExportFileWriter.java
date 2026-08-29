package oj.export;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 导出文件生成：XLSX（成绩汇总 + 提交明细双工作表）或 ZIP（summary.csv + submission-details.csv）。
 * 所有用户可控单元格经 FormulaGuard 消毒；ZIP 使用固定条目名，无路径穿越面。
 */
public final class ExportFileWriter {

    private static final String SUMMARY_SHEET = "成绩汇总";
    private static final String DETAIL_SHEET = "提交明细";
    private static final String SUMMARY_CSV = "summary.csv";
    private static final String DETAILS_CSV = "submission-details.csv";

    private ExportFileWriter() {
    }

    public record SummaryRow(int rank, String studentNo, String name, BigDecimal totalScore,
                             BigDecimal passRate, long submissionCount, String statusDistribution,
                             List<String> perProblemScores) {
    }

    public record DetailRow(String studentNo, String name, String problemLabel, int attemptNo,
                            String submittedAt, String language, String judgeStatus,
                            BigDecimal problemScore, long passedCount, long totalCount,
                            long timeMs, long memoryKb) {
    }

    public record ExportDataset(List<String> problemLabels, List<SummaryRow> summary,
                                List<DetailRow> details) {
    }

    public static byte[] writeXlsx(ExportDataset dataset) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            Sheet summary = workbook.createSheet(SUMMARY_SHEET);
            writeHeaderRow(summary, headerStyle, summaryHeaders(dataset));
            int r = 1;
            for (SummaryRow row : dataset.summary()) {
                Row sheetRow = summary.createRow(r++);
                int c = 0;
                sheetRow.createCell(c++).setCellValue(row.rank());
                setText(sheetRow.createCell(c++), row.studentNo());
                setText(sheetRow.createCell(c++), row.name());
                sheetRow.createCell(c++).setCellValue(row.totalScore().doubleValue());
                sheetRow.createCell(c++).setCellValue(row.passRate().doubleValue());
                sheetRow.createCell(c++).setCellValue(row.submissionCount());
                setText(sheetRow.createCell(c++), row.statusDistribution());
                sheetRow.createCell(c++).setCellValue(row.rank());
                for (String score : row.perProblemScores()) {
                    if (score == null || score.isBlank()) {
                        sheetRow.createCell(c++).setCellValue("");
                    } else {
                        sheetRow.createCell(c++).setCellValue(new BigDecimal(score).doubleValue());
                    }
                }
            }
            for (int i = 0; i < summaryHeaders(dataset).length; i++) {
                summary.autoSizeColumn(i);
            }

            Sheet details = workbook.createSheet(DETAIL_SHEET);
            writeHeaderRow(details, headerStyle, detailHeaders());
            int d = 1;
            for (DetailRow row : dataset.details()) {
                Row sheetRow = details.createRow(d++);
                int c = 0;
                setText(sheetRow.createCell(c++), row.studentNo());
                setText(sheetRow.createCell(c++), row.name());
                setText(sheetRow.createCell(c++), row.problemLabel());
                sheetRow.createCell(c++).setCellValue(row.attemptNo());
                setText(sheetRow.createCell(c++), row.submittedAt());
                setText(sheetRow.createCell(c++), row.language());
                setText(sheetRow.createCell(c++), row.judgeStatus());
                sheetRow.createCell(c++).setCellValue(row.problemScore().doubleValue());
                sheetRow.createCell(c++).setCellValue(row.passedCount());
                sheetRow.createCell(c++).setCellValue(row.totalCount());
                sheetRow.createCell(c++).setCellValue(row.timeMs());
                sheetRow.createCell(c++).setCellValue(row.memoryKb());
            }
            for (int i = 0; i < detailHeaders().length; i++) {
                details.autoSizeColumn(i);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    public static byte[] writeCsvZip(ExportDataset dataset) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(SUMMARY_CSV));
            zip.write(summaryCsv(dataset));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(DETAILS_CSV));
            zip.write(detailsCsv(dataset));
            zip.closeEntry();
            zip.finish();
            return out.toByteArray();
        }
    }

    private static String[] summaryHeaders(ExportDataset dataset) {
        List<String> labels = dataset.problemLabels();
        String[] headers = new String[7 + labels.size()];
        headers[0] = "班级排名";
        headers[1] = "学号";
        headers[2] = "姓名";
        headers[3] = "总分";
        headers[4] = "通过率(%)";
        headers[5] = "提交次数";
        headers[6] = "状态分布";
        for (int i = 0; i < labels.size(); i++) {
            headers[7 + i] = labels.get(i);
        }
        return headers;
    }

    private static String[] detailHeaders() {
        return new String[]{
                "学号", "姓名", "题号", "提交序号", "提交时间", "语言", "判题状态",
                "题目得分", "通过测试点数", "测试点总数", "运行时间(ms)", "峰值内存(KB)"};
    }

    private static byte[] summaryCsv(ExportDataset dataset) {
        StringBuilder sb = new StringBuilder("\uFEFF"); // UTF-8 BOM，保证 Excel 识别
        appendCsvRow(sb, summaryHeaders(dataset));
        for (SummaryRow row : dataset.summary()) {
            appendCsvRow(sb, new String[]{
                    String.valueOf(row.rank()),
                    row.studentNo(),
                    row.name(),
                    money(row.totalScore()),
                    money(row.passRate()),
                    String.valueOf(row.submissionCount()),
                    row.statusDistribution()
            }, row.perProblemScores().stream()
                    .map(s -> s == null || s.isBlank() ? "" : money(new BigDecimal(s)))
                    .toArray(String[]::new));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] detailsCsv(ExportDataset dataset) {
        StringBuilder sb = new StringBuilder("\uFEFF"); // UTF-8 BOM，保证 Excel 识别
        appendCsvRow(sb, detailHeaders());
        for (DetailRow row : dataset.details()) {
            appendCsvRow(sb, new String[]{
                    row.studentNo(),
                    row.name(),
                    row.problemLabel(),
                    String.valueOf(row.attemptNo()),
                    row.submittedAt(),
                    row.language(),
                    row.judgeStatus(),
                    money(row.problemScore()),
                    String.valueOf(row.passedCount()),
                    String.valueOf(row.totalCount()),
                    String.valueOf(row.timeMs()),
                    String.valueOf(row.memoryKb())
            });
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** RFC 4180：含逗号/引号/换行的字段加引号并转义；公式注入消毒。 */
    private static void appendCsvRow(StringBuilder sb, String[]... groups) {
        boolean first = true;
        for (String[] group : groups) {
            for (String value : group) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                String sanitized = FormulaGuard.sanitize(value == null ? "" : value);
                if (sanitized.contains("\"") || sanitized.contains(",") || sanitized.contains("\n")
                        || sanitized.contains("\r")) {
                    sb.append('"').append(sanitized.replace("\"", "\"\"")).append('"');
                } else {
                    sb.append(sanitized);
                }
            }
        }
        sb.append("\r\n");
    }

    private static void writeHeaderRow(Sheet sheet, CellStyle style, String[] headers) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(style);
        }
    }

    /** XLSX 文本单元格：先消毒再写入，防止公式注入。 */
    private static void setText(Cell cell, String value) {
        cell.setCellValue(FormulaGuard.sanitize(value));
    }

    private static String money(BigDecimal value) {
        if (value == null) {
            return "0.00";
        }
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
