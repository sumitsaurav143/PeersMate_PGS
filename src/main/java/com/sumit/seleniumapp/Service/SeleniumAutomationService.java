package com.sumit.seleniumapp.Service;

import com.sumit.seleniumapp.Controller.AutomationStatusStream;
import com.sumit.seleniumapp.Factory.DriverFactory;
import com.sumit.seleniumapp.Util.ExcelUtil;
import com.sumit.seleniumapp.Util.LoginDetails;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SeleniumAutomationService {

    @Autowired
    private DriverFactory driverFactory;

    @Autowired
    private ExcelUtil excelUtil;

    @Autowired
    private LoginDetails loginCredentials;

    @Autowired
    AutomationStatusStream automationStatusStream;

    public boolean isAutomationEnabled = false;

    int countFailed = 0;

    int errorMsgCount = 0;

    List<String> errorFarmers = new ArrayList<>();

    List<Map<String, String>> fallbackFarmers = new ArrayList<>();

    WebDriver driver;

    private static final Logger log =
            LoggerFactory.getLogger(SeleniumAutomationService.class);

    private static final int CAPTCHA_LENGTH = 5;

    public void stopAutomation() throws Exception {
        isAutomationEnabled = false;
        log.warn("Stopping Automation Safely.");
        driver.quit();
    }

    public void runAutomation(String userId, String password, String year, String season, String month, String fromDate, String toDate, String filePath) throws Exception {

        /* =========================
           INIT DRIVER
           ========================= */
        isAutomationEnabled= true;

        driver = driverFactory.createDriver();
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(12));
        WebDriverWait LoginWait = new WebDriverWait(driver, Duration.ofSeconds(15));
        WebDriverWait longWait = new WebDriverWait(driver, Duration.ofSeconds(60));

        /* =========================
           LOGIN
           ========================= */
        driver.get(loginCredentials.getUrl());

        new Select(wait.until(ExpectedConditions.elementToBeClickable(
                By.id("userType")))).selectByValue("lcGroup");

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.id("localGroupUserId"))).sendKeys(userId);//loginCredentials.getUsername()

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.id("localGroupPasswordRC"))).sendKeys(password);//loginCredentials.getPassword()

        LoginWait.until(d ->
                d.findElement(By.id("verify"))
                        .getAttribute("value").length() >= CAPTCHA_LENGTH
        );

        driver.findElement(By.id("loginBtn")).click();

        wait.until(ExpectedConditions.urlToBe(
                "https://pgsindia-ncof.gov.in/NF/farmer/listOfGroupMembers"
        ));

        log.info("Redirecting to Peers Appraisals Add Page...");

         /* ==============================
           OPEN PEERS APPRAISAL PAGE
           ============================== */
        driver.navigate().to("https://pgsindia-ncof.gov.in/NF/farmer/peer/add");

        wait.until(ExpectedConditions.urlToBe(
                "https://pgsindia-ncof.gov.in/NF/farmer/peer/add"
        ));
        /* ============
           SELECT YEAR
           ============ */
        log.info("Selecting Year...");
        longWait.until(ExpectedConditions.numberOfElementsToBeMoreThan(
                By.xpath("//select[@id='financialYearMasterId']/option"), 1
        ));

        selectDropdownByText(driver, "financialYearMasterId", year);

         /* ============
           SELECT SEASON
           ============= */
        log.info("Selecting Season...");
        longWait.until(ExpectedConditions.numberOfElementsToBeMoreThan(
                By.xpath("//select[@id='season']/option"), 1
        ));
        selectDropdownByText(driver, "season", season);
        /* ============
           SELECT MONTH
           ============= */
        log.info("Selecting Month...");
        longWait.until(ExpectedConditions.numberOfElementsToBeMoreThan(
                By.xpath("//select[@id='month']/option"), 1
        ));

        selectDropdownByText(driver, "month", month);

        /* =====================
           SELECT RANDOM FARMERS
           ==================== */
        // wait till options are loaded
        log.info("Selecting Random 6 Farmers from List...");
        longWait.until(ExpectedConditions.numberOfElementsToBeMoreThan(
                By.xpath("//select[@id='multiselect']/option"), 6
        ));

        Select select = new Select(driver.findElement(By.id("multiselect")));

        if (!select.isMultiple()) {
            throw new RuntimeException("Dropdown does not support multiple selection");
        }

        List<WebElement> options = select.getOptions();

        // filter valid options (optional but recommended)
        List<WebElement> validOptions = options.stream()
                .filter(o -> o.isEnabled())
                .filter(o -> !o.getText().trim().isEmpty())
                .collect(Collectors.toList());

        if (validOptions.size() < 6) {
            throw new RuntimeException("Less than 6 options available");
        }

        // shuffle and pick first 6
        Collections.shuffle(validOptions);

        for (int i = 0; i < 6; i++) {
            select.selectByVisibleText(validOptions.get(i).getText());
        }

        driver.findElement(By.id("multiselect_rightSelected")).click();

        /* ==================
           SELECT DATE RANGE
           ================== */
        log.info("Selecting Dates of Peers Appraisals...");
        setDateRange(driver, fromDate, toDate);

        /* ==================
           SELECT FARMERS RANGE
           ================== */
        log.info("COUNTING FARMERS...");
        longWait.until(ExpectedConditions.numberOfElementsToBeMoreThan(
                By.xpath("//select[@id='fmrExpYields']/option"), 1
        ));

        WebElement farmerDropdown = driver.findElement(By.id("fmrExpYields"));
        Select sel = new Select(farmerDropdown);

        List<String> allFarmers = sel.getOptions().stream()
                .map(WebElement::getText)
                .filter(t -> !t.trim().isEmpty())
                .filter(t -> !t.equalsIgnoreCase("--Select--"))
                .toList();

        log.info("Total farmers to process: " + allFarmers.size());

        int totalExcelRows = excelUtil.getRowCount(filePath);
        log.info("Total farmers found in Excel: {}", totalExcelRows);

        Set<String> usedFarmers = new HashSet<>();

        boolean allSuccess = true;


        if (totalExcelRows>0) {
            log.info("Proceeding farmers details from Excel");
            for (int row = 1; row <= totalExcelRows; row++) {

                if(!isAutomationEnabled){
                    allSuccess=false;
                    break;
                }

                log.info("===== Processing Farmer Row {} =====", row);

                try {
                    Map<String, String> farmer = excelUtil.getRowData(filePath, row);
                    addFarmerDetails(farmer, driver, wait, longWait);

                    log.info("✅ Farmer row {} insert done", row);

                    try {
                        Thread.sleep(3500);

                        longWait.until(ExpectedConditions.numberOfElementsToBeMoreThan(
                                By.xpath("//select[@id='fmrExpYields']/option"), 1
                        ));

                        List<WebElement> totalFarmersLeft = driver.findElements(
                                By.xpath("//select[@id='fmrExpYields']/option[not(contains(@style,'display: none'))]")
                        );



                        log.info("🌾 Farmers left in dropdown: {}", totalFarmersLeft.size()-1);
                        log.info("Error Farmer Counts ::" + errorMsgCount);

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                } catch (Exception e) {

                    log.error("❌ Farmer row {} failed: {}", row, e.getMessage(), e);
                    allSuccess = false;

                    if (!isAutomationEnabled) {
                        log.warn("Automation stopped by user");
                        break;
                    }
                }
            }

            log.warn("TOTAL AFFECTED FARMERS::" + fallbackFarmers.size());

            if(errorMsgCount==0 && allSuccess) {
                log.info("All farmers added.. Saving data..");
                // now click
                WebDriverWait wait2 = new WebDriverWait(driver, Duration.ofSeconds(10));

                By submitBtn = By.id("submitBtnNext1");

                WebElement button = wait2.until(
                        ExpectedConditions.presenceOfElementLocated(submitBtn)
                );

                ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", button);

                Thread.sleep(1000);

                wait2.until(ExpectedConditions.elementToBeClickable(button));

                try {
                    button.click();
                } catch (ElementClickInterceptedException e) {
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", button);
                }

                Thread.sleep(1000);

                /* ===================
                 STEP : SELECT CLAUSE
               ======================== */

                    JavascriptExecutor js = (JavascriptExecutor) driver;

                    // Check if success alert exists and is visible
                    Boolean isSuccessAlertPresent = (Boolean) js.executeScript(
                            "var el = document.querySelector('.alert.alert-success');" +
                                    "return el !== null && el.offsetParent !== null;"
                    );

                    if (Boolean.TRUE.equals(isSuccessAlertPresent)) {

                        js.executeScript(
                                "document.querySelectorAll(\"label.checkcontainer input[type='radio'][value='Y']\")" +
                                        ".forEach(r => {" +
                                        "   r.checked = true;" +
                                        "   r.dispatchEvent(new Event('change', { bubbles: true }));" +
                                        "});"
                        );

                        log.info("All clause radios with value 'Y' selected via JS");




                    //SELECT MEMBERS STATUS - PGS NATURAL

                    js.executeScript(
                            "document.querySelectorAll(\"select[name='complingFarmerStatus']\").forEach(s => {" +
                                    "  const opt = [...s.options].find(o => o.text.trim() === 'PGS NATURAL');" +
                                    "  if (opt) {" +
                                    "    s.value = opt.value;" +
                                    "    s.dispatchEvent(new Event('change', { bubbles: true }));" +
                                    "  }" +
                                    "});"
                    );

                    log.info("All members status selected to - PGS NATURAL ");

                    // SCROLL TILL declaration

                    By declarationCheckbox = By.id("declaration");

                    WebElement buttonDec = wait2.until(
                            ExpectedConditions.presenceOfElementLocated(declarationCheckbox)
                    );

                    ((JavascriptExecutor) driver)
                            .executeScript("arguments[0].scrollIntoView({block:'center'});", buttonDec);

                    wait2.until(ExpectedConditions.elementToBeClickable(buttonDec));

                    try {
                        if (!buttonDec.isSelected()) {
                            buttonDec.click();
                        }
                    } catch (ElementClickInterceptedException e) {
                        ((JavascriptExecutor) driver)
                                .executeScript("arguments[0].checked = true; arguments[0].dispatchEvent(new Event('change', {bubbles:true}));",
                                        buttonDec);
                    }

                    log.info("Automation Done!!");
                } else {
                    log.info("Success alert not present. Please verify manually!.");
                }

            }
        } else {
            //log.info("Not Proceeding farmers from Excel :: LESS RECORDS PRESENT IN EXCEL SHEET!!");
            log.info("Stopping Automation!!");
            automationStatusStream.notifyStatus("FAILED");
        }

    }


    /* =========================
       HELPER METHODS
       ========================= */

    public void setDateRange(WebDriver driver, String fromDate, String toDate) {

        JavascriptExecutor js = (JavascriptExecutor) driver;

        WebElement from = driver.findElement(By.id("peerAppraisalFromDateStr"));
        WebElement to = driver.findElement(By.id("peerAppraisalToDateStr"));

        js.executeScript("arguments[0].value = arguments[1];", from, fromDate);
        js.executeScript("arguments[0].value = arguments[1];", to, toDate);

        // trigger change event if app listens to it
        js.executeScript("arguments[0].dispatchEvent(new Event('change'));", from);
        js.executeScript("arguments[0].dispatchEvent(new Event('change'));", to);
    }


    private void selectCropMatchingPeas(String cropName, WebDriver driver, WebDriverWait wait) {

        wait.until(ExpectedConditions.presenceOfElementLocated(By.id("cropName")));
        Select cropSelect = new Select(
                wait.until(ExpectedConditions.elementToBeClickable(By.id("cropName")))
        );

        List<WebElement> freshOptions = cropSelect.getOptions();

        boolean selected = false;

        for (WebElement option : freshOptions) {
            String text = option.getText(); // SAFE now
            if (text != null && text.toUpperCase().contains(cropName)) {
                cropSelect.selectByVisibleText(text);
                selected = true;
                break;
            }
        }

        if (!selected) {
            throw new RuntimeException(cropName + " :: Crop not found");
        }
    }

    private boolean selectMatchingFarmer(
            String farmerName,
            WebDriver driver,
            WebDriverWait wait) {

        log.info("Searching Farmer :: {}", farmerName);

        WebElement selectElement = wait.until(
                ExpectedConditions.presenceOfElementLocated(By.id("fmrExpYields")));

        List<WebElement> allOptions =
                selectElement.findElements(By.tagName("option"));

        // Keep options that are displayed and enabled.
        List<WebElement> visibleOptions = allOptions.stream()
                .filter(WebElement::isDisplayed)
                .filter(WebElement::isEnabled)
                .toList();

        log.info("Visible farmers count: {}", visibleOptions.size());

        String expectedName = normalizeName(
                farmerName.replaceAll("\\s*\\(.*?\\)", "").trim());

        for (WebElement option : visibleOptions) {
            String optionText = option.getText();
            if (optionText == null || optionText.isBlank()) {
                continue;
            }

            String actualName = normalizeName(
                    optionText.replaceAll("\\s*\\(.*?\\)", "").trim());

            log.debug("Comparing farmer: expected='{}', option='{}'",
                    expectedName, actualName);

            if (actualName.equals(expectedName)) {
                wait.until(ExpectedConditions.elementToBeClickable(option));
                option.click();

                log.info("Selected Farmer :: {}", optionText);
                return true;
            }
        }

        log.warn("No selectable farmer matched for {}", farmerName);
        return false;
    }

    private void fillInput(WebDriverWait wait, String id, String value) {
        WebElement input = wait.until(
                ExpectedConditions.visibilityOfElementLocated(By.id(id)));
        input.clear();
        log.info("FILLING FIELD [{}] WITH VALUE [{}]", id, value);
        input.sendKeys(value);
    }

    private void selectDropdownByText(WebDriver driver, String id, String text) {

        Select select = new Select(driver.findElement(By.id(id)));

        String normalizedInput = normalize(text);

        for (WebElement option : select.getOptions()) {
            String normalizedOption = normalize(option.getText());

            if (normalizedOption.equals(normalizedInput)) {
                option.click();
                return;
            }
        }

        throw new RuntimeException(
                "Option '" + text + "' not found for dropdown with id: " + id
        );
    }

    private String normalize(String value) {
        if (value == null) return "";

        value = value.trim().toLowerCase();

        // Handle GEN <-> GENERAL mapping
        if (value.equals("gen")) return "General";
        if (value.equals("general")) return "General";
        if (value.equals("m")) return "Male";
        if (value.equals("male")) return "Male";
        if (value.equals("f")) return "Female";
        if (value.equals("female")) return "Female";

        return value;
    }

    private String normalizeName(String name) {
        return name.toLowerCase()
                .replaceAll("[^a-z ]", "")   // remove special chars
                .replaceAll("\\s+", "")      // remove ALL spaces
                .trim();
    }


    private void acceptAlertIfPresent(WebDriver driver, int seconds) {
        try {
            WebDriverWait shortWait =
                    new WebDriverWait(driver, Duration.ofSeconds(seconds));

            Alert alert = shortWait.until(ExpectedConditions.alertIsPresent());
            log.info("Alert :: {}", alert.getText());
            alert.accept();

        } catch (TimeoutException e) {
            log.info("No alert present, continuing...");
        }
    }

    private void addFarmerDetails(Map<String, String> farmer, WebDriver driver, WebDriverWait wait, WebDriverWait longWait) {
        {
            log.info("Selected Farmer: " + farmer.toString());

            String farmerName = farmer.get("Farmer Name");
            String cgName = farmer.get("Crop Category").toUpperCase();
            String cropName = farmer.get("Crop*").toUpperCase();
            String areaShowInHa = farmer.get("Area Shown in (Ha)*");
            String expQuintals = farmer.get("Expected Yields in (Quintals)*");
            String selfQuintals = farmer.get("Self Consumption in (Quintals)*");

            // ===== Select Farmer =====
            boolean matched = selectMatchingFarmer(farmerName, driver, wait);

            if (!matched) {
                showContinueMessage(farmerName);
                return;
            }else {
                //  ===== Select CG =====
                WebElement cgDropdown = wait.until(
                        ExpectedConditions.elementToBeClickable(By.id("cgName"))
                );

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                Select cgSelect = new Select(cgDropdown);

                boolean found = false;

                for (WebElement option : cgSelect.getOptions()) {
                    if (option.getText().trim().equalsIgnoreCase(cgName.trim())) {
                        cgSelect.selectByVisibleText(option.getText()); // select original text
                        found = true;
                        break;
                    }
                }


                if (!found) {
                    throw new RuntimeException("CG name not found (ignore-case): " + cgName);
                }

                // ===== Wait crop reload =====
                wait.until(ExpectedConditions.numberOfElementsToBeMoreThan(
                        By.xpath("//select[@id='cropName']/option"), 1
                ));
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                // ✅ SAFE crop selection
                selectCropMatchingPeas(cropName, driver, wait);
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                // ===== Inputs =====
                driver.findElement(By.id("areaShowInHa")).clear();
                driver.findElement(By.id("areaShowInHa")).sendKeys(areaShowInHa);
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                driver.findElement(By.id("expQuintals")).clear();
                driver.findElement(By.id("expQuintals")).sendKeys(expQuintals);
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                driver.findElement(By.id("selfQuintals")).clear();
                driver.findElement(By.id("selfQuintals")).sendKeys(selfQuintals);
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                // ===== Add =====
                WebElement addButton = wait.until(
                        ExpectedConditions.elementToBeClickable(
                                By.cssSelector("button.addfarmerYieldsBtn")
                        )
                );

                addButton.click();

                //check errors - Handling for retry
                WebElement errorLabelCrop = driver.findElement(By.id("cropNameErrMsg"));

                WebElement errorLabelArea = driver.findElement(By.id("areaShowInHaErrMsg"));

                String errorMsgCrop = errorLabelCrop.getText().trim();
                String errorMsgArea = errorLabelArea.getText().trim();

                if (!errorMsgCrop.isEmpty()) {
                    log.info("❌ Error message present: {}", errorMsgCrop);
                    errorMsgCount++;
                    errorFarmers.add(farmerName + "-cropError");
                }

                if (!errorMsgArea.isEmpty()) {
                    log.info("❌ Error message present: {}", errorMsgArea);
                    errorMsgCount++;
                    errorFarmers.add(farmerName + "-areaError");
                }

                if (!errorMsgArea.isEmpty() || !errorMsgCrop.isEmpty()) {
                    fallbackFarmers.add(farmer); // store full farmer row
                }

                if (errorMsgArea.isEmpty() && errorMsgCrop.isEmpty()) {
                    log.info("Farmer details added: {}", farmerName);
                }

            }
            wait.until(ExpectedConditions.presenceOfElementLocated(By.id("fmrExpYields")));
        }

    }

    private void showContinueMessage(String farmerName) {
        countFailed++;
        log.warn(
                "⚠️ Farmer not found: {}. Skipping and continuing automation. " +
                        "Total failed count: {}",
                farmerName,
                countFailed
        );
    }

    private void showFinalAlert(WebDriver driver, int countFailed) {
        String message;
        if (countFailed > 0) {
            message =
                    "Automation completed.\n\n" +
                            "Some entries could not be inserted: " + countFailed + "\n" +
                            "Please verify and save the data manually.";
        } else {
            message =
                    "Automation completed successfully.\n\n" +
                            "All entries were inserted.";
        }

        JavascriptExecutor js = (JavascriptExecutor) driver;
        js.executeScript("alert(arguments[0]);", message);
        Alert alert = driver.switchTo().alert();
        alert.accept();
    }
}


