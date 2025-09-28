package com.example.Attendence.service;

import com.example.Attendence.model.*;
import com.example.Attendence.repository.AttendanceDataRepository;
import com.example.Attendence.repository.GlobalSettingRepository;
import com.example.Attendence.repository.LocalSettingRepository;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFConditionalFormattingRule;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFSheetConditionalFormatting;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.chrono.ChronoLocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AttendanceService {
    LocalDate databaseDate=null;
    List<Employee> employeeList;
    @Autowired
    UserService userService;

    @Autowired
    LocalSettingRepository localSettingRepository;

    @Autowired
    GlobalSettingRepository globalSettingRepository;

    @Autowired
    private AttendanceDataRepository attendanceDataRepository;



    /**
     * Polished Excel export:
     * - Title + date range + employee info (merged, centered)
     * - Bold colored header, borders, zebra rows, freeze header, autofilter
     * - Proper date/time/duration formats
     * - Conditional formatting for Comment (Present/Absent/Leave/Holiday)
     * - Summary counts + total hours
     * - Signature row at bottom with two blocks: COO (left) and OEO (right)
     * - Landscape, fit-to-width, autosize columns
     */


    public void exportSummaryAttendanceData(List<AttendanceDataForAnyPeriod> dataList, HttpServletResponse response) {
        // Guard
        if (dataList == null || dataList.isEmpty()) {
            try {
                response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                response.getWriter().write("No data to export");
            } catch (IOException ignored) {}
            return;
        }

        // Sort by date (expecting yyyy-MM-dd)
        dataList.sort(Comparator.comparing(AttendanceDataForAnyPeriod::getDate));

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sh = wb.createSheet("Employee Attendance");

            // ======= Styles =======
            // Fonts
            Font titleFont = wb.createFont(); titleFont.setBold(true); titleFont.setFontHeightInPoints((short)14);
            Font headerFont = wb.createFont(); headerFont.setBold(true); headerFont.setColor(IndexedColors.WHITE.getIndex());
            Font normalFont = wb.createFont();

            // Title style
            CellStyle titleStyle = wb.createCellStyle();
            titleStyle.setFont(titleFont);
            titleStyle.setAlignment(HorizontalAlignment.CENTER);
            titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            // Subheader style (date range / employee info)
            CellStyle subHeader = wb.createCellStyle();
            subHeader.setAlignment(HorizontalAlignment.CENTER);
            subHeader.setVerticalAlignment(VerticalAlignment.CENTER);

            // Header style
            CellStyle header = wb.createCellStyle();
            header.setFont(headerFont);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            setThinBorder(header);

            // Data styles
            DataFormat fmt = wb.createDataFormat();

            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(fmt.getFormat("yyyy-mm-dd"));
            dateStyle.setAlignment(HorizontalAlignment.CENTER);
            setThinBorder(dateStyle);

            CellStyle clockStyle = wb.createCellStyle(); // time of day
            clockStyle.setDataFormat(fmt.getFormat("h:mm AM/PM"));
            clockStyle.setAlignment(HorizontalAlignment.CENTER);
            setThinBorder(clockStyle);

            CellStyle durationStyle = wb.createCellStyle(); // duration like [h]:mm
            durationStyle.setDataFormat(fmt.getFormat("[h]:mm"));
            durationStyle.setAlignment(HorizontalAlignment.CENTER);
            setThinBorder(durationStyle);

            CellStyle numberStyle = wb.createCellStyle();
            numberStyle.setDataFormat(fmt.getFormat("0.0")); // out time
            numberStyle.setAlignment(HorizontalAlignment.CENTER);
            setThinBorder(numberStyle);

            CellStyle textStyle = wb.createCellStyle();
            textStyle.setAlignment(HorizontalAlignment.CENTER);
            setThinBorder(textStyle);

            // Zebra (alt row) styles
            CellStyle zebraText = wb.createCellStyle(); zebraText.cloneStyleFrom(textStyle);
            zebraText.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            zebraText.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle zebraClock = wb.createCellStyle(); zebraClock.cloneStyleFrom(clockStyle);
            zebraClock.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            zebraClock.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle zebraDate = wb.createCellStyle(); zebraDate.cloneStyleFrom(dateStyle);
            zebraDate.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            zebraDate.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle zebraDuration = wb.createCellStyle(); zebraDuration.cloneStyleFrom(durationStyle);
            zebraDuration.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            zebraDuration.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle zebraNumber = wb.createCellStyle(); zebraNumber.cloneStyleFrom(numberStyle);
            zebraNumber.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            zebraNumber.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Signature style (line + centered label)
            Font signFont = wb.createFont();
            signFont.setBold(true);
            CellStyle signStyle = wb.createCellStyle();
            signStyle.setAlignment(HorizontalAlignment.CENTER);
            signStyle.setVerticalAlignment(VerticalAlignment.BOTTOM);
            signStyle.setBorderTop(BorderStyle.MEDIUM);
            signStyle.setFont(signFont);

            // ======= Title + period + employee =======
            final String[] headers = {
                    "Date","Entry Time","Late Duration","Entry Comment","Exit Time",
                    "Time After Exit","Exit Comment","Out Time","Total Time in Day",
                    "Day Comment","Comment"
            };
            int colCount = headers.length;

            int rowIdx = 0;

            // Title row
            Row r0 = sh.createRow(rowIdx++);
            r0.setHeightInPoints(24);
            createMergedCell(r0, 0, colCount-1, "Employee Monthly Attendance Summary", titleStyle, sh);

            // Period + employee row
            AttendanceDataForAnyPeriod first = dataList.get(0);
            String employeeName = safe(first.getEmployeeName());
            String employeeId   = safe(first.getEmployeeId());
            String startLabel = "Starting Date: " + safe(first.getStartDate());
            String endLabel   = "End Date: " + safe(first.getEndDate());
            String empLabel   = "Employee: " + employeeName + (employeeId.isEmpty() ? "" : " (" + employeeId + ")");

            Row r1 = sh.createRow(rowIdx++);
            createMergedCell(r1, 0, 3, startLabel, subHeader, sh);
            createMergedCell(r1, 4, 7, endLabel,   subHeader, sh);
            createMergedCell(r1, 8, colCount-1, empLabel, subHeader, sh);

            // Summary row (counts placeholders)
            Row r2 = sh.createRow(rowIdx++);

            // ------- Spacer (gap) row before the table header -------
            Row gapRow = sh.createRow(rowIdx++);
            gapRow.setHeightInPoints(6);

            // Header row
            Row head = sh.createRow(rowIdx++);
            for (int c = 0; c < colCount; c++) {
                Cell cell = head.createCell(c);
                cell.setCellValue(headers[c]);
                cell.setCellStyle(header);
            }

            int dataStartRow = rowIdx; // (0-based row index in POI; Excel shows +1)

            // ======= Data rows =======
            for (AttendanceDataForAnyPeriod d : dataList) {
                Row row = sh.createRow(rowIdx++);
                boolean zebra = ((rowIdx - dataStartRow) % 2 == 0); // every other row

                // Date (yyyy-MM-dd)
                writeDateCell(row, 0, d.getDate(), zebra ? zebraDate : dateStyle);

                // Clock times
                writeClockCell(row, 1, d.getEntryTime(), zebra ? zebraClock : clockStyle);
                // Duration HH:MM
                writeDurationCell(row, 2, d.getLateDuration(), zebra ? zebraDuration : durationStyle);

                writeTextCell(row, 3, d.getEntryComment(), zebra ? zebraText : textStyle);

                writeClockCell(row, 4, d.getExitTime(), zebra ? zebraClock : clockStyle);
                writeDurationCell(row, 5, d.getTimeAfterExit(), zebra ? zebraDuration : durationStyle);

                writeTextCell(row, 6, d.getExitComment(), zebra ? zebraText : textStyle);

                writeNumberCell(row, 7, d.getOutTime(), zebra ? zebraNumber : numberStyle);

                writeDurationCell(row, 8, d.getTotalTimeInDay(), zebra ? zebraDuration : durationStyle);

                writeTextCell(row, 9, d.getDayComment(), zebra ? zebraText : textStyle);
                writeTextCell(row, 10, d.getComment(), zebra ? zebraText : textStyle);
            }

            int dataEndRow = rowIdx - 1;

            // ======= AutoFilter + Freeze Pane =======
            sh.setAutoFilter(new CellRangeAddress(dataStartRow-1, dataEndRow, 0, colCount-1)); // include header row
            sh.createFreezePane(0, dataStartRow); // freeze everything above data

            // ======= Conditional Formatting for "Comment" column (K) =======
            int commentCol = 10;
            XSSFSheetConditionalFormatting cf = sh.getSheetConditionalFormatting();
            addEqualsRule(cf, dataStartRow, dataEndRow, commentCol, "PRESENT", IndexedColors.LIGHT_GREEN);
            addEqualsRule(cf, dataStartRow, dataEndRow, commentCol, "ABSENT",  IndexedColors.ROSE);
            addEqualsRule(cf, dataStartRow, dataEndRow, commentCol, "LEAVE",   IndexedColors.LIGHT_YELLOW);
            addEqualsRule(cf, dataStartRow, dataEndRow, commentCol, "HOLIDAY", IndexedColors.LIGHT_TURQUOISE);

            // ======= Summary formulas (counts) =======
            int firstData = dataStartRow + 1; // Excel 1-based
            int lastData  = dataEndRow + 1;
            String commentRange = String.format("$%s$%d:$%s$%d", colToRef(commentCol), firstData, colToRef(commentCol), lastData);



            // Total hours (sum duration column "Total Time in Day" = index 8)


            // ======= Signature row (same line: COO | OEO) =======
            rowIdx++; // spacer (optional)
            Row sig = sh.createRow(rowIdx++);
            sig.setHeightInPoints(28); // room for handwriting feel

            // Left signature block -> columns 0..4  (A..E)
            createMergedCell(sig, 0, 4, "Chief Operating Officer", signStyle, sh);

            // Leave column 5 (F) as a gap

            // Right signature block -> columns 6..10 (G..K)
            createMergedCell(sig, 6, 10, "Office Executing Officer", signStyle, sh);

            // ======= Print & sizing =======
            sh.getPrintSetup().setLandscape(true);
            sh.setFitToPage(true);
            sh.getPrintSetup().setFitWidth((short)1);
            sh.getPrintSetup().setFitHeight((short)0);
            sh.setMargin(Sheet.LeftMargin, 0.3);
            sh.setMargin(Sheet.RightMargin, 0.3);
            sh.setMargin(Sheet.TopMargin, 0.5);
            sh.setMargin(Sheet.BottomMargin, 0.5);

            // Autosize columns
            for (int c = 0; c < colCount; c++) sh.autoSizeColumn(c, true);

            // ======= Write response =======
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String filename = "Employee_Report_" + ts + ".xlsx";
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

            try (ServletOutputStream out = response.getOutputStream()) {
                wb.write(out);
                out.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    /* ----------------- helpers ----------------- */

    private static void setThinBorder(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static void createMergedCell(Row row, int c1, int c2, String text, CellStyle style, Sheet sh) {
        int r = row.getRowNum();
        sh.addMergedRegion(new CellRangeAddress(r, r, c1, c2));
        for (int c = c1; c <= c2; c++) {
            Cell cell = row.getCell(c) != null ? row.getCell(c) : row.createCell(c);
            if (c == c1 && text != null) cell.setCellValue(text);
            cell.setCellStyle(style);
        }
    }

    private static Cell ensureCell(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) cell = row.createCell(col);
        return cell;
    }

    private static void writeTextCell(Row row, int col, String val, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(safe(val).isEmpty() ? "—" : val);
        cell.setCellStyle(style);
    }

    private static void writeNumberCell(Row row, int col, String val, CellStyle style) {
        Cell cell = row.createCell(col);
        try {
            if (val != null && !val.isBlank()) {
                cell.setCellValue(Double.parseDouble(val));
            } else {
                cell.setCellValue("");
            }
        } catch (NumberFormatException e) {
            cell.setCellValue(safe(val));
        }
        cell.setCellStyle(style);
    }

    private static void writeDateCell(Row row, int col, String yyyyMmDd, CellStyle style) {
        Cell cell = row.createCell(col);
        try {
            if (yyyyMmDd != null && !yyyyMmDd.isBlank()) {
                LocalDate d = LocalDate.parse(yyyyMmDd);
                cell.setCellValue(java.sql.Date.valueOf(d));
            } else {
                cell.setCellValue("");
            }
        } catch (Exception ex) {
            cell.setCellValue(safe(yyyyMmDd));
        }
        cell.setCellStyle(style);
    }

    private static void writeClockCell(Row row, int col, String clock, CellStyle style) {
        Cell cell = row.createCell(col);
        Double excel = toExcelTimeFromClock(clock);
        if (excel != null) {
            cell.setCellValue(excel);
        } else {
            cell.setCellValue(safe(clock)); // fallback as text
        }
        cell.setCellStyle(style);
    }

    private static void writeDurationCell(Row row, int col, String duration, CellStyle style) {
        Cell cell = row.createCell(col);
        Double excel = toExcelTimeFromDuration(duration);
        if (excel != null) {
            cell.setCellValue(excel);
        } else {
            cell.setCellValue(safe(duration));
        }
        cell.setCellStyle(style);
    }

    /** Convert "10:07 AM" / "09:00 PM" / "17:45[:SS]" into Excel time (fraction of day). */
    private static Double toExcelTimeFromClock(String v) {
        if (v == null || v.isBlank() || v.equals("—") || v.equals("-")) return null;
        String s = v.trim();
        try {
            // 12h with AM/PM
            DateTimeFormatter f = DateTimeFormatter.ofPattern("h:mm[:ss] a").withLocale(java.util.Locale.ENGLISH);
            LocalTime t = LocalTime.parse(s.toUpperCase(), f);
            return (t.toSecondOfDay()) / 86400d;
        } catch (Exception ignore) {}
        try {
            // 24h
            DateTimeFormatter f24 = DateTimeFormatter.ofPattern("H:mm[:ss]");
            LocalTime t = LocalTime.parse(s, f24);
            return (t.toSecondOfDay()) / 86400d;
        } catch (Exception ignore) {}
        return null;
    }

    /** Convert "H:MM[:SS]" or "1h 20m" or "7.5h" into Excel duration ([h]:mm). */
    private static Double toExcelTimeFromDuration(String v) {
        if (v == null || v.isBlank() || v.equals("—") || v.equals("-")) return null;
        String s = v.trim();
        try {
            // H:MM[:SS]
            String[] parts = s.split(":");
            if (parts.length >= 2 && parts.length <= 3) {
                int h = Integer.parseInt(parts[0]);
                int m = Integer.parseInt(parts[1]);
                int sec = (parts.length == 3) ? Integer.parseInt(parts[2]) : 0;
                int totalSec = h * 3600 + m * 60 + sec;
                return totalSec / 86400d;
            }
        } catch (Exception ignore) {}
        try {
            // "1h 20m 5s" / "45m" / "30s" / "7.5h"
            int h = 0, m = 0, sec = 0;
            String lower = s.toLowerCase();
            if (lower.contains("h") || lower.contains("m") || lower.contains("s")) {
                java.util.regex.Matcher mm = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*h").matcher(lower);
                if (mm.find()) {
                    double hd = Double.parseDouble(mm.group(1));
                    h = (int) hd;
                    m += (int) Math.round((hd - h) * 60);
                }
                mm = java.util.regex.Pattern.compile("(\\d+)\\s*m").matcher(lower);
                if (mm.find()) m += Integer.parseInt(mm.group(1));
                mm = java.util.regex.Pattern.compile("(\\d+)\\s*s").matcher(lower);
                if (mm.find()) sec += Integer.parseInt(mm.group(1));
                return (h * 3600 + m * 60 + sec) / 86400d;
            }
            // plain decimal hours "7.5"
            double hd = Double.parseDouble(lower);
            return (hd * 3600) / 86400d;
        } catch (Exception ignore) {}
        return null;
    }

    private static void addEqualsRule(XSSFSheetConditionalFormatting cf, int rowStart, int rowEnd, int col, String word, IndexedColors fill) {
        String colRef = colToRef(col);
        String formula = "UPPER($" + colRef + (rowStart+1) + ")=\"" + word + "\"";
        XSSFConditionalFormattingRule rule = cf.createConditionalFormattingRule(formula);
        PatternFormatting pf = rule.createPatternFormatting();
        pf.setFillPattern(PatternFormatting.SOLID_FOREGROUND);
        pf.setFillForegroundColor(fill.getIndex());
        CellRangeAddress[] regions = { new CellRangeAddress(rowStart, rowEnd, col, col) };
        cf.addConditionalFormatting(regions, rule);
    }

    /** 0->A, 10->K, etc. */
    private static String colToRef(int colIdxZeroBased) {
        int c = colIdxZeroBased;
        StringBuilder sb = new StringBuilder();
        while (c >= 0) {
            int rem = c % 26;
            sb.insert(0, (char)('A' + rem));
            c = (c / 26) - 1;
        }
        return sb.toString();
    }



    public List<AttendanceDataForAnyPeriod> getAttendanceDataForAnyPeriod(
            String employeeId, String employeeName, String startDateStr, String endDateStr, String header) {

        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate startDate = LocalDate.parse(startDateStr, dateFormatter);
        LocalDate endDate = LocalDate.parse(endDateStr, dateFormatter);

        List<AttendanceDataForAnyPeriod> resultList = new ArrayList<>();
        List<AttendanceData> dataList = attendanceDataRepository
                .findByEmployeeIdAndUpdateStatusAndEntryDateInclusive(employeeId, "1", startDateStr, endDateStr);

        List<Employee> employeeList = userService.employeeList(header);

        if (!dataList.isEmpty()) {
            for (Employee f : employeeList) {
                for (AttendanceData e : dataList) {
                    LocalDate entryDate;
                    try {
                        entryDate = LocalDate.parse(e.getEntryDate(), dateFormatter);
                    } catch (DateTimeParseException ex) {
                        ex.printStackTrace();
                        continue;
                    }

                    boolean dateMatch = (!entryDate.isBefore(startDate) && !entryDate.isAfter(endDate));
                    boolean nameMatch = f.getName().equals(e.getName());

                    if (dateMatch && nameMatch && employeeName.equals(e.getName())) {
                        String date = e.getEntryDate();

                        switch (e.getStatus()) {
                            case "Leave", "Holiday" -> {
                                String symbol = "➖";
                                resultList.add(new AttendanceDataForAnyPeriod(
                                        employeeId, employeeName, startDateStr, endDateStr, date,
                                        symbol, symbol, symbol, symbol, symbol, symbol, symbol, symbol, symbol, e.getStatus()
                                ));
                            }
                            case "Absent" -> {
                                String symbol = "❌";
                                resultList.add(new AttendanceDataForAnyPeriod(
                                        employeeId, employeeName, startDateStr, endDateStr, date,
                                        symbol, symbol, symbol, symbol, symbol, symbol, symbol, symbol, symbol, e.getStatus()
                                ));
                            }
                        }
                        if (!e.getStatus().equals("Leave") & !e.getStatus().equals("Holiday") & !e.getStatus().equals("Absent")) {

                            DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("hh:mm a");

                            // Entry time logic
                            String entryTimeStr = e.getEntryTime().format(timeFormatter);
                            LocalTime entryTime = e.getEntryTime().toLocalTime();
                            LocalTime lateThreshold = LocalTime.of(
                                    returnSettingStartHour(e.getEmployeeId(), e.getName(), e.getEntryDate()),
                                    returnGlobalSettingLateMinute(e.getEntryDate())
                            );
                            LocalTime lateCheckTime = LocalTime.of(
                                    returnSettingStartHour(e.getEmployeeId(), e.getName(), e.getEntryDate()),
                                    returnSettingStartMinute(e.getEmployeeId(), e.getName(), e.getEntryDate())
                            );

                            String lateDuration;
                            String entryComment;
                            if (entryTime.isAfter(lateThreshold)) {
                                Duration duration = Duration.between(lateCheckTime, entryTime);
                                lateDuration = String.format("%d:%02d", duration.toHoursPart(), duration.toMinutesPart());
                                entryComment = "Late";
                            } else {
                                Duration duration = entryTime.isAfter(lateCheckTime)
                                        ? Duration.between(lateCheckTime, entryTime)
                                        : Duration.ZERO;
                                lateDuration = String.format("%d:%02d", duration.toHoursPart(), duration.toMinutesPart());
                                entryComment = "In Time";
                            }

                            // Exit time logic
                            String exitTimeStr = e.getExitTime().format(timeFormatter);
                            LocalTime exitTime = e.getExitTime().toLocalTime();
                            String[] earlyThresholdParts = subtractHourMinute(
                                    returnSettingEndHour(e.getEmployeeId(), e.getName(), e.getEntryDate()),
                                    returnGlobalSettingEarlyMinute(e.getEntryDate())
                            );
                            LocalTime overtimeThreshold = LocalTime.of(
                                    Integer.parseInt(earlyThresholdParts[0]),
                                    Integer.parseInt(earlyThresholdParts[1])
                            );
                            LocalTime standardExitTime = LocalTime.of(
                                    returnSettingEndHour(e.getEmployeeId(), e.getName(), e.getEntryDate()),
                                    returnSettingEndMinute(e.getEmployeeId(), e.getName(), e.getEntryDate())
                            );

                            String overtimeDuration;
                            String exitComment;
                            if (exitTime.isBefore(overtimeThreshold)) {
                                Duration duration = Duration.between(exitTime, standardExitTime);
                                overtimeDuration = "-" + String.format("%d:%02d", duration.toHours(), duration.toMinutesPart());
                                exitComment = "Early";
                            } else {
                                Duration duration = Duration.between(standardExitTime, exitTime);
                                overtimeDuration = String.format("%d:%02d", duration.toHours(), duration.toMinutesPart());
                                exitComment = "Ok";
                            }

                            // Total day duration
                            Duration dayDuration = Duration.between(e.getEntryTime(), e.getExitTime());
                            String totalDayDuration = String.format("%d:%02d", dayDuration.toHoursPart(), dayDuration.toMinutesPart());

                            int expectedHours = returnSettingTotalHour(e.getEmployeeId(), e.getName(), e.getEntryDate());
                            String dayComment = checkTimeDifference(dayDuration, expectedHours);

                            resultList.add(new AttendanceDataForAnyPeriod(
                                    employeeId,
                                    employeeName,
                                    startDateStr,
                                    endDateStr,
                                    date,
                                    entryTimeStr,
                                    lateDuration,
                                    entryComment,
                                    exitTimeStr,
                                    overtimeDuration,
                                    exitComment,
                                    e.getOuttime(),
                                    totalDayDuration,
                                    dayComment,
                                    e.getStatus()
                            ));
                        }
                    }
                }
            }
        }

        return resultList;
    }

    public static LocalTime convertUtcToDhakaLocalTime(LocalTime utcTime) {
        // Combine UTC time with today's date in UTC
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDateTime utcDateTime = LocalDateTime.of(today, utcTime);

        // Attach UTC zone
        ZonedDateTime utcZoned = utcDateTime.atZone(ZoneOffset.UTC);

        // Convert to Asia/Dhaka time zone
        ZonedDateTime dhakaZoned = utcZoned.withZoneSameInstant(ZoneId.of("Asia/Dhaka"));

        // Return only the time part
        return dhakaZoned.toLocalTime();
    }
    public ResponseEntity<String> updateAttendanceData(List<AttendanceDataForFixedDay> newData, List<AttendanceDataForFixedDay> oldData) {
        List<AttendanceDataForFixedDay> changedData = new ArrayList<>();

        // Step 1: Detect changed records
        for (AttendanceDataForFixedDay oldRecord : oldData) {
            Optional<AttendanceDataForFixedDay> matchingNewRecord = newData.stream()
                    .filter(newRecord -> newRecord.getEmployeeId().equals(oldRecord.getEmployeeId())
                            && newRecord.getDate().equals(oldRecord.getDate()))
                    .findFirst();

            if (matchingNewRecord.isPresent()) {
                AttendanceDataForFixedDay newRecord = matchingNewRecord.get();

                // Manually compare fields instead of using equals()
                if (!hasSameData(oldRecord, newRecord)) {
                    changedData.add(newRecord); // ✅ Store newRecord instead of oldRecord
                }
            }
        }



        // Step 2: Update old records' status to "0" (Only for changed records)
        changedData.forEach(e -> {
            Optional<AttendanceData> data = attendanceDataRepository.findByEmployeeIdAndEntryDateAndUpdateStatus(e.getEmployeeId(), e.getDate(), "1");
            data.ifPresent(view -> {
                view.setUpdateStatus("0");
                attendanceDataRepository.save(view);
            });
        });

        // Step 3: Save only changed records
        changedData.forEach(e -> {
            LocalDate entryDate = LocalDate.parse(e.getDate(), DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            LocalDate exitDate = entryDate;

            AttendanceData attendanceData = new AttendanceData(
                    e.getEmployeeId(),
                    e.getName(),
                    Integer.toString(entryDate.getMonthValue()),
                    Integer.toString(entryDate.getYear()),
                    createLocalDateTime(entryDate.getYear(), entryDate.getMonthValue(), entryDate.getDayOfMonth(), Integer.parseInt(e.getStartHour()), Integer.parseInt(e.getStartMinute()), e.getStartPeriod()),
                    e.getLateEntryReason(),
                    createLocalDateTime(exitDate.getYear(), exitDate.getMonthValue(), exitDate.getDayOfMonth(), Integer.parseInt(e.getExitHour()), Integer.parseInt(e.getExitMinute()), e.getExitPeriod()),
                    e.getEarlyExitReason(),
                    e.getStatus(),
                    e.getOutHour() + "." + e.getOutMinute(),
                    entryDate.toString(),
                    LocalDateTime.now(),
                    "1",
                    e.getGlobalDayStatus()
            );

            attendanceDataRepository.save(attendanceData);
        });

        return ResponseEntity.ok("Successfully Updated");
    }


    private boolean hasSameData(AttendanceDataForFixedDay oldRecord, AttendanceDataForFixedDay newRecord) {
        boolean isSame = oldRecord.getStartHour().equals(newRecord.getStartHour()) &&
                oldRecord.getStartMinute().equals(newRecord.getStartMinute()) &&
                oldRecord.getExitHour().equals(newRecord.getExitHour()) &&
                oldRecord.getExitMinute().equals(newRecord.getExitMinute()) &&
                oldRecord.getEarlyExitReason().equals(newRecord.getEarlyExitReason()) &&
                oldRecord.getLateEntryReason().equals(newRecord.getLateEntryReason()) &&
                oldRecord.getOutHour().equals(newRecord.getOutHour()) &&
                oldRecord.getOutMinute().equals(newRecord.getOutMinute()) &&
                oldRecord.getGlobalDayStatus().equals(newRecord.getGlobalDayStatus()) &&
                oldRecord.getStatus().equals(newRecord.getStatus());



        return isSame;
    }

    public LocalDateTime createLocalDateTime(int year, int month, int dayOfMonth, int hour, int minute, String amPm) {
        int adjustedHour = hour % 12;
        if (amPm.equalsIgnoreCase("PM")) {
            adjustedHour += 12;
        }
        return LocalDateTime.of(year, month, dayOfMonth, adjustedHour, minute);
    }

    public static String[] subtractHourMinute(int hour, int minutesToSubtract) {
        // Convert everything to minutes for easier calculation
        int totalMinutes = hour * 60 ;
        totalMinutes -= minutesToSubtract;

        // Handle negative result
        if (totalMinutes < 0) {
            totalMinutes += 24 * 60; // Assuming a 24-hour clock
        }

        // Calculate resulting hour and minute
        int resultingHour = totalMinutes / 60;
        int resultingMinute = totalMinutes % 60;

        // Format the result
        String[] result = new String[2];
        result[0] = String.valueOf(resultingHour);
        result[1] = String.valueOf(resultingMinute);
        return result;
    }
    public static String checkTimeDifference(Duration duration , int totalHours) {

        long hours = duration.toHoursPart();
        long minutes = duration.toMinutesPart();

        String result;
        if (hours < totalHours ) {
            result = "Short time";
        } else if (hours > totalHours|| (hours == totalHours && minutes > 0)) {
            result = "Extra time";
        } else {
            result = "Required time";
        }

        return result;
    }
    public int returnGlobalSettingLateMinute(String insertDataDateStr) {
        int defaultMinute = 9;

        LocalDate insertDate;
        try {
            insertDate = LocalDate.parse(insertDataDateStr); // expects format: yyyy-MM-dd
        } catch (DateTimeParseException e) {
            e.printStackTrace();
            return defaultMinute;
        }

        return globalSettingRepository.findAllByStatus("1").stream()
                .sorted(Comparator.comparing(GlobalSetting::getFormattedBirthDate).reversed()) // reverse for latest first
                .map(setting -> {
                    try {
                        LocalDate start = LocalDate.parse(setting.getFormattedBirthDate());
                        LocalDate end = LocalDate.parse(setting.getFormattedDeathDate());
                        if (!insertDate.isBefore(start) && !insertDate.isAfter(end)) {
                            return Integer.parseInt(setting.getLateMinute());
                        }
                    } catch (DateTimeParseException e) {
                        e.printStackTrace();
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(defaultMinute);
    }

    public int returnGlobalSettingEarlyMinute(String insertDataDateStr) {
        int defaultMinute = 9;

        LocalDate insertDate;
        try {
            insertDate = LocalDate.parse(insertDataDateStr); // expects format "yyyy-MM-dd"
        } catch (DateTimeParseException e) {
            e.printStackTrace();
            return defaultMinute;
        }

        return globalSettingRepository.findAllByStatus("1").stream()
                .sorted(Comparator.comparing(GlobalSetting::getFormattedBirthDate).reversed())
                .map(setting -> {
                    try {
                        LocalDate start = LocalDate.parse(setting.getFormattedBirthDate());
                        LocalDate end = LocalDate.parse(setting.getFormattedDeathDate());

                        if (!insertDate.isBefore(start) && !insertDate.isAfter(end)) {
                            return Integer.parseInt(setting.getEarlyMinute());
                        }
                    } catch (DateTimeParseException e) {
                        e.printStackTrace();
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(defaultMinute);
    }

    public int returnSettingStartMinute(String id, String name, String insertDataDateStr) {
        int defaultMinute = 9;

        LocalDate insertDate;
        try {
            insertDate = LocalDate.parse(insertDataDateStr); // expects "yyyy-MM-dd"
        } catch (DateTimeParseException e) {
            e.printStackTrace();
            return defaultMinute;
        }

        return localSettingRepository.findAllByStatus("1").stream()
                .filter(s -> id.equals(s.getEmployeeId()) && name.equals(s.getName()))
                .sorted(Comparator.comparing(LocalSetting::getFormattedBirthDate).reversed())
                .map(setting -> {
                    try {
                        LocalDate start = LocalDate.parse(setting.getFormattedBirthDate());
                        LocalDate end = LocalDate.parse(setting.getFormattedDeathDate());

                        if (!insertDate.isBefore(start) && !insertDate.isAfter(end)) {
                            return Integer.parseInt(setting.getStartMinute());
                        }
                    } catch (DateTimeParseException e) {
                        e.printStackTrace();
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(defaultMinute);
    }

    public int returnSettingEndHour(String id, String name, String insertDataDateStr) {
        int defaultHour = 17;

        LocalDate insertDate;
        try {
            insertDate = LocalDate.parse(insertDataDateStr);
        } catch (DateTimeParseException e) {
            e.printStackTrace();
            return defaultHour;
        }

        return localSettingRepository.findAllByStatus("1").stream()
                .filter(s -> id.equals(s.getEmployeeId()) && name.equals(s.getName()))
                .sorted(Comparator.comparing(LocalSetting::getFormattedBirthDate).reversed()) // assume dates are in "yyyy-MM-dd"
                .map(setting -> {
                    try {
                        LocalDate startDate = LocalDate.parse(setting.getFormattedBirthDate());
                        LocalDate endDate = LocalDate.parse(setting.getFormattedDeathDate());

                        if (!insertDate.isBefore(startDate) && !insertDate.isAfter(endDate)) {
                            return Integer.parseInt(setting.getEndHours());
                        }
                    } catch (DateTimeParseException e) {
                        e.printStackTrace();
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(defaultHour);
    }


    public int returnSettingEndMinute(String id, String name, String insertDataDateStr) {
        int defaultMinute = 9;

        LocalDate insertDate;
        try {
            insertDate = LocalDate.parse(insertDataDateStr); // Expects format "yyyy-MM-dd"
        } catch (DateTimeParseException e) {
            e.printStackTrace();
            return defaultMinute;
        }

        return localSettingRepository.findAllByStatus("1").stream()
                .filter(s -> id.equals(s.getEmployeeId()) && name.equals(s.getName()))
                .sorted(Comparator.comparing(LocalSetting::getFormattedBirthDate).reversed())
                .map(setting -> {
                    try {
                        LocalDate start = LocalDate.parse(setting.getFormattedBirthDate());
                        LocalDate end = LocalDate.parse(setting.getFormattedDeathDate());

                        if (!insertDate.isBefore(start) && !insertDate.isAfter(end)) {
                            return Integer.parseInt(setting.getEndMinute());
                        }
                    } catch (DateTimeParseException e) {
                        e.printStackTrace();
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(defaultMinute);
    }

    public int returnSettingStartHour(String id, String name, String insertDataDateStr) {
        int defaultHour = 9;

        LocalDate insertDate;
        try {
            insertDate = LocalDate.parse(insertDataDateStr); // expects format "yyyy-MM-dd"
        } catch (DateTimeParseException e) {
            e.printStackTrace();
            return defaultHour;
        }

        return localSettingRepository.findAllByStatus("1").stream()
                .filter(s -> id.equals(s.getEmployeeId()) && name.equals(s.getName()))
                .sorted(Comparator.comparing(LocalSetting::getFormattedBirthDate).reversed())
                .map(setting -> {
                    try {
                        LocalDate start = LocalDate.parse(setting.getFormattedBirthDate());
                        LocalDate end = LocalDate.parse(setting.getFormattedDeathDate());

                        if (!insertDate.isBefore(start) && !insertDate.isAfter(end)) {
                            return Integer.parseInt(setting.getStartHours());
                        }
                    } catch (DateTimeParseException e) {
                        e.printStackTrace();
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(defaultHour);
    }

    public int returnSettingTotalHour(String id, String name, String insertDataDateStr) {
        int defaultHour = 8;

        LocalDate insertDate;
        try {
            insertDate = LocalDate.parse(insertDataDateStr); // expects format "yyyy-MM-dd"
        } catch (DateTimeParseException e) {
            e.printStackTrace();
            return defaultHour;
        }

        return localSettingRepository.findAllByStatus("1").stream()
                .filter(s -> id.equals(s.getEmployeeId()) && name.equals(s.getName()))
                .sorted(Comparator.comparing(LocalSetting::getFormattedBirthDate).reversed())
                .map(setting -> {
                    try {
                        LocalDate start = LocalDate.parse(setting.getFormattedBirthDate());
                        LocalDate end = LocalDate.parse(setting.getFormattedDeathDate());

                        if (!insertDate.isBefore(start) && !insertDate.isAfter(end)) {
                            return Integer.parseInt(setting.getTotalHours());
                        }
                    } catch (DateTimeParseException e) {
                        e.printStackTrace();
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(defaultHour);
    }

    public static Duration addMinutesToDuration(Duration originalDuration, long minutesToAdd) {
        return originalDuration.plusMinutes(minutesToAdd);
    }
}
