# PeersMate PGS Automation

PeersMate is a Spring Boot and Selenium desktop web application that automates
entry of farmer and crop details into the PGS portal using an Excel workbook.
The UI validates the workbook, starts and monitors automation, and provides
live row counts and progress. Completed runs provide a status workbook and a
session log for download.

## Requirements

- Java Development Kit (JDK) 25
- Google Chrome
- macOS, Windows, or Linux

The project targets Java 25. Maven Wrapper scripts are included, so a separate
Maven installation is not required.

## Run from source

From the project directory:

```bash
./mvnw spring-boot:run
```

On Windows, run `mvnw.cmd spring-boot:run`.

Open [http://localhost:8080](http://localhost:8080) in a browser. The app uses
WebDriverManager to prepare ChromeDriver for the installed Chrome browser.

## Build and run the JAR

Build and run the tests:

```bash
./mvnw clean package
```

Run the packaged application:

```bash
java -jar target/PeersMate-0.0.5.jar
```

Then open [http://localhost:8080](http://localhost:8080).

## Excel workbook

Upload an `.xlsx` workbook with its column headers in the first row. These
columns are required:

- `Farmer Name`
- `Crop Category`
- `Crop*`
- `Area Shown in (Ha)*`
- `Expected Yields in (Quintals)*`
- `Self Consumption in (Quintals)*`

The first worksheet is processed. Blank rows are ignored. Validate the workbook
in the UI before starting automation.

## Automation and run results

- The dashboard displays processed, successful, failed, and skipped row counts,
  current progress, and elapsed time.
- Missing farmer, crop-category, or crop values are retried up to three total
  attempts. If the value still cannot be found, that row is marked failed and
  the automation continues.
- Completed rows are marked `SUCCESS` or `FAILED` in the workbook, with a
  `MESSAGE` column containing row details. Previously successful rows are
  skipped when a results workbook is run again.
- When a run finishes, download its results workbook and automation log from
  the UI.

By default, uploads and run artifacts are stored under `~/PeersMate/uploads`,
and application logs are stored under `~/PeersMate/logs`. Result files use the
sanitized portal user ID as a prefix:

- `<userId>_Report_status.xlsx`
- `<userId>_Automation_logs.log`

Files with the same user ID are reused by later runs.

## Configuration

Application settings are in `src/main/resources/application.properties`.
In particular:

- `app.url` configures the PGS portal URL.
- `app.upload.dir` configures where uploaded and result workbooks are stored.
- `logging.file.name` configures the application log path.

These settings support Spring property overrides, including environment
variables and command-line arguments.
