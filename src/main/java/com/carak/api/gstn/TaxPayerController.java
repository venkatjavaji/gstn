package com.carak.api.gstn;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
@Slf4j
public class TaxPayerController {

    private final TaxPayerService taxpayerService;

    public TaxPayerController(TaxPayerService taxpayerService) {
        this.taxpayerService = taxpayerService;
    }

    @GetMapping("/test")
    public String testPage(RedirectAttributes redirectAttributes) {

        redirectAttributes.addFlashAttribute("success","testing..");
        log.info("test redirect");
          return "redirect:/?month=April&year=2024";
    }

    @GetMapping("/")
    public String showTaxPayerPage(Model model) {
        model.addAttribute("months", List.of( "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"));
        model.addAttribute("years", List.of("2020", "2021", "2022", "2023", "2024", "2025"));
        return "fetch_taxpayer_details";
    }

    @PostMapping("/fetch-taxpayer-details")
    public String fetchTaxpayerDetails(
            @RequestParam("month") String month,
            @RequestParam("year") String year,
            @RequestParam("file") MultipartFile file,
            RedirectAttributes redirectAttributes) {
        try {
            if (file.isEmpty()) {
                redirectAttributes.addFlashAttribute("error", "File is required.");
                redirectAttributes.addFlashAttribute("month", month);
                redirectAttributes.addFlashAttribute("year", year);
                return "redirect:/";
            }

            String fileType = file.getContentType();
            Set<String> gstins = taxpayerService.extractGstinsFromFile(file, fileType);

            if (gstins.isEmpty()) {
                redirectAttributes.addFlashAttribute("error", "No GSTINs found in the uploaded file.");
                redirectAttributes.addFlashAttribute("month", month);
                redirectAttributes.addFlashAttribute("year", year);
                return "redirect:/";
            }

            // Generate CSV file and store it temporarily
            File tempFile = taxpayerService.generateCsvFile(gstins, month, year);
            redirectAttributes.addFlashAttribute("success", "File processed successfully.");
            redirectAttributes.addFlashAttribute("fileReady", true);
            redirectAttributes.addFlashAttribute("filePath", tempFile.getAbsolutePath());
            redirectAttributes.addFlashAttribute("encodedFilePath",URLEncoder.encode(tempFile.getAbsolutePath(), StandardCharsets.UTF_8) );
            redirectAttributes.addFlashAttribute("month", month);
            redirectAttributes.addFlashAttribute("year", year);
            return "redirect:/";

        } catch (Exception e) {
            log.error("Error processing taxpayer details: {}", e.getMessage());
            redirectAttributes.addFlashAttribute("error", "An error occurred: " + e.getMessage());
            redirectAttributes.addFlashAttribute("fileReady", false);
            redirectAttributes.addFlashAttribute("month", month);
            redirectAttributes.addFlashAttribute("year", year);
            return "redirect:/";
        }
    }

    @PostMapping("/download-file")
    public ResponseEntity<StreamingResponseBody> downloadFile(@RequestBody FileDownloadRequest request) {
        String filePath = request.getFilePath();

        try {
            if (filePath == null || filePath.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(output -> output.write("File path is missing or empty.".getBytes()));
            }

            Path path = Paths.get(filePath.trim());
            if (!Files.exists(path) || !Files.isReadable(path)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(output -> output.write("The file does not exist or is not readable.".getBytes()));
            }

            Resource resource = new UrlResource(path.toUri());

            StreamingResponseBody stream = outputStream -> {
                try (InputStream inputStream = resource.getInputStream()) {
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                    outputStream.flush();
                } finally {
                    // Delete the file after streaming is complete
                    try {
                        Files.deleteIfExists(path);
                        log.info("Temporary file deleted: " + path.toString());
                    } catch (IOException e) {
                       log.error("Failed to delete temporary file: " + path.toString());
                    }
                }
            };

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
                    .body(stream);

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(output -> output.write(("An error occurred: " + e.getMessage()).getBytes()));
        }
    }

}
