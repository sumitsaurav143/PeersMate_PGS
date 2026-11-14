package com.sumit.seleniumapp.Util;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExcelUtilTests {

    @TempDir
    Path tempDir;

    private final ExcelUtil excelUtil = new ExcelUtil();

    @Test
    void preservesActualRowIndexesAndRetryStatusInWorkbook() throws Exception {
        Path workbookPath = tempDir.resolve("farmers.xlsx");
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet();
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Farmer Name");
            header.createCell(1).setCellValue("Crop Category");
            sheet.createRow(1).createCell(0).setCellValue("First Farmer");
            sheet.createRow(4).createCell(0).setCellValue("Retry Farmer");

            try (FileOutputStream output = new FileOutputStream(workbookPath.toFile())) {
                workbook.write(output);
            }
        }

        assertEquals(List.of(1, 4), excelUtil.getDataRowIndexes(workbookPath.toString()));
        assertEquals(2, excelUtil.getRowCount(workbookPath.toString()));

        excelUtil.writeResult(workbookPath.toString(), 4, "FAILED", "Crop not found");
        Map<String, String> retryRow = excelUtil.getRowData(workbookPath.toString(), 4);

        assertEquals("FAILED", retryRow.get("STATUS"));
        assertEquals("Crop not found", retryRow.get("MESSAGE"));
    }
}
