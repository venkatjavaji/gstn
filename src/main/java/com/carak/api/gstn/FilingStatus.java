package com.carak.api.gstn;

import lombok.Data;

@Data
public class FilingStatus {
    private String fy;
    private String taxp;
    private String mof;
    private String dof;
    private String rtntype;
    private String arn;
    private String status;
}
