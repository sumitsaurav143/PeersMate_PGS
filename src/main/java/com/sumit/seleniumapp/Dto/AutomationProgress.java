package com.sumit.seleniumapp.Dto;

public record AutomationProgress(
        int totalRows,
        int processedRows,
        int successfulRows,
        int failedRows,
        int skippedRows,
        String currentRow) {
}
