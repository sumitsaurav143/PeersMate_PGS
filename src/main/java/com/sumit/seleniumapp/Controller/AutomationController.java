package com.sumit.seleniumapp.Controller;

import com.sumit.seleniumapp.Dto.ExcelValidationResult;
import com.sumit.seleniumapp.Dto.AutomationProgress;
import com.sumit.seleniumapp.Service.SeleniumAutomationService;
import com.sumit.seleniumapp.Util.ExcelUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@RestController
public class AutomationController {

    private static final Logger log = LogManager.getLogger(AutomationController.class);
    @Autowired
    private SeleniumAutomationService service;

    @Autowired
    ExcelUtil excelUtil;

    @Value("${logging.file.name}")
    private String logFilePath;

    @Value("${app.upload.dir}")
    private String uploadDirPath;

    private final ConcurrentMap<String, RunInfo> runs = new ConcurrentHashMap<>();

    @PostMapping("/validate")
    public ResponseEntity<ExcelValidationResult> validateExcel(
            @RequestParam("file") MultipartFile file) {

        Path tempFile = null;

        try {
            // ✅ Basic validation
            if (file.isEmpty()) {
                throw new IllegalArgumentException("Uploaded file is empty");
            }

            if (!file.getOriginalFilename().endsWith(".xlsx")) {
                throw new IllegalArgumentException("Only .xlsx files are supported");
            }

            // ✅ Safe temp file creation
            tempFile = Files.createTempFile(
                    "peersmate_validate_",
                    "_" + file.getOriginalFilename()
            );

            file.transferTo(tempFile.toFile());

            // ✅ Validate
            ExcelValidationResult result =
                    excelUtil.validateExcel(tempFile.toString());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Excel validation failed", e);

            return ResponseEntity.badRequest().body(
                    new ExcelValidationResult(
                            false,
                            List.of(),
                            List.of(e.getMessage())
                    )
            );

        } finally {
            // ✅ CRITICAL: cleanup temp file
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("Failed to delete temp file {}", tempFile);
                }
            }
        }
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> startAutomation(
            @RequestParam("userId") String userId,
            @RequestParam("password") String password,
            @RequestParam("year") String year,
            @RequestParam("season") String season,
            @RequestParam("month") String month,
            @RequestParam("fromDate") String fromDate,
            @RequestParam("toDate") String toDate,
            @RequestParam("file") MultipartFile file) {

        String runId = UUID.randomUUID().toString();
        RunInfo runInfo = new RunInfo();
        runs.put(runId, runInfo);

        new Thread(() -> {
            String finalStatus = "FAILED";
            long logStartPosition = 0;
            try {
                runInfo.status = "RUNNING";
                Path uploadDir = Paths.get(uploadDirPath);
                Files.createDirectories(uploadDir);

                Path logPath = Paths.get(logFilePath);
                if (Files.isRegularFile(logPath)) {
                    logStartPosition = Files.size(logPath);
                }

                String filePrefix = safeFilePrefix(userId);
                Path filePath = uploadDir.resolve(filePrefix + "_Report_status.xlsx");
                runInfo.logFile = uploadDir.resolve(filePrefix + "_Automation_logs.log");

                log.info("Automation started for user :: {}", userId);

                file.transferTo(filePath.toFile());
                runInfo.resultFile = filePath;

                log.info("File saved to path :: {}", filePath);

                service.runAutomation(userId, password, year, season, month, fromDate, toDate,
                        filePath.toString(), progress -> runInfo.progress = progress);
                finalStatus = service.isAutomationEnabled ? "COMPLETED" : "STOPPED";

            } catch (Exception e) {
                log.error("Automation failed", e);
            } finally {
                try {
                    if (runInfo.logFile != null) {
                        saveRunLog(Paths.get(logFilePath), logStartPosition, runInfo.logFile);
                    }
                } catch (IOException e) {
                    runInfo.logFile = null;
                    log.error("Unable to save automation session log for run {}", runId, e);
                }
                runInfo.status = finalStatus;
            }
        }).start();

        Map<String, String> response = new HashMap<>();
        response.put("status", "STARTED");
        response.put("runId", runId);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<Map<String, Object>> getRunStatus(@PathVariable String runId) {
        RunInfo runInfo = runs.get(runId);
        if (runInfo == null) {
            return ResponseEntity.notFound().build();
        }
        AutomationProgress progress = runInfo.progress;
        return ResponseEntity.ok(Map.of(
                "status", runInfo.status,
                "resultsAvailable", runInfo.resultFile != null,
                "logsAvailable", runInfo.logFile != null,
                "totalRows", progress.totalRows(),
                "processedRows", progress.processedRows(),
                "successfulRows", progress.successfulRows(),
                "failedRows", progress.failedRows(),
                "skippedRows", progress.skippedRows(),
                "currentRow", progress.currentRow()));
    }

    @GetMapping("/runs/{runId}/results")
    public ResponseEntity<Resource> downloadRunResults(@PathVariable String runId) {
        RunInfo runInfo = runs.get(runId);
        if (runInfo == null) {
            return ResponseEntity.notFound().build();
        }
        if ("RUNNING".equals(runInfo.status) || runInfo.resultFile == null) {
            return ResponseEntity.notFound().build();
        }

        Path resultFile = runInfo.resultFile;
        if (!Files.isRegularFile(resultFile)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + resultFile.getFileName() + "\"")
                .body(new FileSystemResource(resultFile));
    }

    @GetMapping("/runs/{runId}/logs")
    public ResponseEntity<Resource> downloadRunLogs(@PathVariable String runId) {
        RunInfo runInfo = runs.get(runId);
        if (runInfo == null || runInfo.logFile == null
                || "RUNNING".equals(runInfo.status)
                || "STARTING".equals(runInfo.status)) {
            return ResponseEntity.notFound().build();
        }

        Path logFile = runInfo.logFile;
        if (!Files.isRegularFile(logFile)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + logFile.getFileName() + "\"")
                .body(new FileSystemResource(logFile));
    }

    @PostMapping("/stop")
    public String stopAutomation() {

        new Thread(() -> {
            try {
                service.stopAutomation();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        return "Automation stopped";
    }


    @GetMapping(value = "/logs", produces = "text/plain")
    public String getLogs() {

        Path logPath = Paths.get(logFilePath);

        try {
            if (!Files.exists(logPath)) {
                return "Logs not available yet...";
            }

            return Files.readString(logPath);

        } catch (Exception e) {
            return "❌ Unable to read logs: " + e.getMessage();
        }
    }

    private static class RunInfo {
        private volatile String status = "STARTING";
        private volatile Path resultFile;
        private volatile Path logFile;
        private volatile AutomationProgress progress = new AutomationProgress(0, 0, 0, 0, 0, "");
    }

    private String safeFilePrefix(String userId) {
        String prefix = userId == null ? "" : userId.trim()
                .replaceAll("[^A-Za-z0-9._-]+", "_")
                .replaceAll("^\\.+|\\.+$", "");
        return prefix.isBlank() ? "user" : prefix;
    }

    private void saveRunLog(Path sourceLog, long startPosition, Path destination) throws IOException {
        if (!Files.isRegularFile(sourceLog)) {
            Files.writeString(destination, "Application log file was not available for this run.",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return;
        }

        try (FileChannel source = FileChannel.open(sourceLog, StandardOpenOption.READ);
             FileChannel target = FileChannel.open(destination, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            long currentSize = source.size();
            long position = Math.min(startPosition, currentSize);
            long remaining = currentSize - position;
            while (remaining > 0) {
                long transferred = source.transferTo(position, remaining, target);
                if (transferred <= 0) {
                    ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(8192, remaining));
                    source.position(position);
                    int read = source.read(buffer);
                    if (read < 0) break;
                    buffer.flip();
                    while (buffer.hasRemaining()) target.write(buffer);
                    transferred = read;
                }
                position += transferred;
                remaining -= transferred;
            }
        }
    }
}