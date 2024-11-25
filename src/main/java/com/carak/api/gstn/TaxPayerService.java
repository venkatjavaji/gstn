package com.carak.api.gstn;

import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;
import com.opencsv.exceptions.CsvException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class TaxPayerService {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${gstn.api.url}")
    private String API_URL;

    public Set<String> extractGstinsFromFile(MultipartFile file, String fileType) throws Exception {
        if ("text/csv".equals(fileType)) {
            return extractGstinsFromCsv(file);
        } else if ("application/vnd.ms-excel".equals(fileType)  || "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(fileType)) {
            return extractGstinsFromExcel(file);
        } else {
            throw new IllegalArgumentException("Unsupported file type. Only CSV or Excel files are supported.");
        }
    }

    public Set<String> extractGstinsFromCsv(MultipartFile file) throws IOException {
        Set<String> gstins = new HashSet<>();
        try (CSVReader reader = new CSVReader(new InputStreamReader(file.getInputStream()))) {
            List<String[]> allRows = reader.readAll();

            if (allRows.isEmpty()) {
                throw new IllegalArgumentException("Invalid file. No data found.");
            }

            // Get the header row
            String[] header = allRows.get(0);
            int gstnColumnIndex = getGstnColumnIndex(header);

            // Process the data rows
            for (int i = 1; i < allRows.size(); i++) {
                String[] row = allRows.get(i);
                if (gstnColumnIndex < row.length) {
                    String gstn = row[gstnColumnIndex].trim();
                    if (!gstn.isEmpty()) {
                        gstins.add(gstn.replaceAll("\"", "")); // Remove quotes if present
                    }
                }
            }
        } catch (CsvException e) {
            throw new IllegalArgumentException("Error processing the CSV file.", e);
        }
        return gstins;
    }

    private int getGstnColumnIndex(String[] header) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].trim().equalsIgnoreCase("gstn")) {
                return i;
            }
        }
        throw new IllegalArgumentException("GSTN column not found in header.");
    }

    public Set<String> extractGstinsFromExcel(MultipartFile file) throws IOException {
        Set<String> gstins = new HashSet<>();
        try (InputStream inputStream = file.getInputStream(); Workbook workbook = WorkbookFactory.create(inputStream)) {

            // Process the first sheet
            Sheet sheet = workbook.getSheetAt(0);

            // Find the correct header row dynamically
            int headerRowIndex = findHeaderRowIndex(sheet);
            Row headerRow = sheet.getRow(headerRowIndex);

            if (headerRow == null) {
                throw new IllegalArgumentException("Invalid file. Header row is missing.");
            }

            // Get the GSTN column index
            int gstnColumnIndex = getGstnColumnIndex(headerRow);

            // Extract GSTINs from data rows
            for (int i = headerRowIndex + 1; i <= sheet.getLastRowNum(); i++) { // Start after header
                Row row = sheet.getRow(i);
                if (row != null && row.getCell(gstnColumnIndex) != null) {
                    String cellValue = row.getCell(gstnColumnIndex).toString().trim();
                    if (!cellValue.isEmpty()) {
                        gstins.add(cellValue);
                    }
                }
            }
        } catch (EncryptedDocumentException e) {
            throw new IllegalArgumentException("The file is encrypted and cannot be processed.", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Error reading the file.", e);
        }
        return gstins;
    }

    private int findHeaderRowIndex(Sheet sheet) {
        // Check the first two rows for a valid header
        for (int i = 0; i <= 1; i++) {
            Row row = sheet.getRow(i);
            if (isHeaderRow(row)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Valid header row not found in the first two rows.");
    }

    private boolean isHeaderRow(Row row) {
        if (row == null) {
            return false;
        }
        for (int i = 0; i < row.getLastCellNum(); i++) {
            if (row.getCell(i) != null) {
                String cellValue = row.getCell(i).toString().trim().toLowerCase();
                if ("gstn".equals(cellValue)) {
                    return true; // Valid header row if "GSTN" is found
                }
            }
        }
        return false;
    }

    private int getGstnColumnIndex(Row headerRow) {
        for (int i = 0; i < headerRow.getLastCellNum(); i++) {
            if (headerRow.getCell(i) != null && "gstn".equalsIgnoreCase(headerRow.getCell(i).getStringCellValue().trim())) {
                return i;
            }
        }
        throw new IllegalArgumentException("GSTN column not found in header.");
    }

    /**
     * Generate CSV for responses fetched from the API.
     *
     * @param gstins List of GSTINs.
     * @param month  Selected month.
     * @param year   Selected year.
     * @return File object pointing to the saved CSV file.
     * @throws IOException if file creation fails.
     */
    public File generateCsvFile(Set<String> gstins, String month, String year) throws IOException, InterruptedException {
        List<ApiResponse> responses = fetchAllResponses(gstins, month, year);

        File tempFile = Files.createTempFile("filing_" + month + "_" + year, ".csv").toFile();
        try (CSVWriter writer = new CSVWriter(new FileWriter(tempFile))) {
            writer.writeNext(new String[]{"GSTN", "FY", "Tax Period", "Mode of Filing",
                    "Date of Filing", "Return Type", "ARN", "Status"});

            for (ApiResponse response : responses) {
                if (response.getFilingStatus() == null || response.getFilingStatus().isEmpty()) {
                    writer.writeNext(new String[]{
                            response.getGstn(), "", "", "", "", "", "", response.getStatus()
                    });
                } else {
                    response.getFilingStatus().forEach(filingStatuses ->
                            filingStatuses.forEach(status ->
                                    writer.writeNext(new String[]{
                                            response.getGstn(), status.getFy(), status.getTaxp(),
                                            status.getMof(), status.getDof(), status.getRtntype(),
                                            status.getArn(), status.getStatus()
                                    })
                            )
                    );
                }
            }
        }
        log.info("CSV file generated at {}", tempFile.getAbsolutePath());
        return tempFile;
    }

    /**
     * Fetch responses for all GSTINs from the API.
     *
     * @param gstins List of GSTINs.
     * @param month  Selected month.
     * @param year   Selected year.
     * @return List of ApiResponse objects.
     */
    public List<ApiResponse> fetchAllResponses(Set<String> gstins, String month, String year) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(10); // Adjust thread pool as needed

        // Make parallel API calls and collect futures
        List<Future<ApiResponse>> futures = gstins.stream()
                .map(gstin -> executor.submit(() -> getTaxpayerReturnDetails(gstin,year)))
                .collect(Collectors.toList());

        List<ApiResponse> responses = new ArrayList<>();

        // Process each future result
        for (Future<ApiResponse> future : futures) {
            try {
                ApiResponse response = future.get();
                responses.add(processResponse(response, month));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);
        return responses;
    }

    // Make API call
    public ApiResponse getTaxpayerReturnDetails(String gstin, String year) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> payload = Map.of("gstin", gstin, "fy", year);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(payload, headers);

        ResponseEntity<ApiResponse> response = restTemplate.postForEntity(API_URL, entity, ApiResponse.class);
        ApiResponse apiResponse = response.getBody();
        if(apiResponse == null ) throw new RuntimeException("apiResponse is null ");
        apiResponse.setGstn(gstin);
        return response.getBody();
    }

    // Helper method to process a single API response for a specific month
    private ApiResponse processResponse(ApiResponse response, String month) {
        // Create a new ApiResponse to hold filtered data
        ApiResponse filteredResponse = new ApiResponse();
        filteredResponse.setGstn(response.getGstn());
        filteredResponse.setBusinessUnit(response.getBusinessUnit());

        if (response.getFilingStatus() == null || response.getFilingStatus().isEmpty()) {
            filteredResponse.setFilingStatus(createNoRecordFilingStatus(month));
        } else {
            List<FilingStatus> statuses = response.getFilingStatus().stream()
                    .flatMap(List::stream)
                    .filter(status -> status.getTaxp().equalsIgnoreCase(month))
                    .collect(Collectors.toList());

            // Ensure both GSTR1 and GSTR3B are included
            List<FilingStatus> filteredStatuses = new ArrayList<>();
            filteredStatuses.add(getOrCreateStatus(statuses, "GSTR1", month));
            filteredStatuses.add(getOrCreateStatus(statuses, "GSTR3B", month));

            filteredResponse.setFilingStatus(List.of(filteredStatuses));
        }

        filteredResponse.setStatus("Processed");
        return filteredResponse;
    }

    // Create "No Record Found" entries for both GSTR1 and GSTR3B
    private List<List<FilingStatus>> createNoRecordFilingStatus(String month) {
        FilingStatus noRecordGSTR1 = new FilingStatus();
        noRecordGSTR1.setRtntype("GSTR1");
        noRecordGSTR1.setTaxp(month);
        noRecordGSTR1.setStatus("No Record Found");

        FilingStatus noRecordGSTR3B = new FilingStatus();
        noRecordGSTR3B.setRtntype("GSTR3B");
        noRecordGSTR3B.setTaxp(month);
        noRecordGSTR3B.setStatus("No Record Found");

        return List.of(List.of(noRecordGSTR1, noRecordGSTR3B));
    }

    // Get the status for a specific return type or create a "No Record Found" entry
    private FilingStatus getOrCreateStatus(List<FilingStatus> statuses, String rtntype, String month) {
        return statuses.stream()
                .filter(status -> status.getRtntype().equalsIgnoreCase(rtntype))
                .findFirst()
                .orElseGet(() -> {
                    FilingStatus noRecord = new FilingStatus();
                    noRecord.setRtntype(rtntype);
                    noRecord.setTaxp(month);
                    noRecord.setStatus("No Record Found");
                    return noRecord;
                });
    }

    public File getTempFile(String month, String year) {
        return new File(System.getProperty("java.io.tmpdir"), "filing_" + month + "_" + year + ".csv");
    }
}
