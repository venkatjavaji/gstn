package com.carak.api.gstn;

import lombok.Data;
import java.util.List;

@Data
public class ApiResponse {
    private List<List<FilingStatus>> filingStatus;
    private String status;
    private String gstn;
    private String businessUnit;
}
