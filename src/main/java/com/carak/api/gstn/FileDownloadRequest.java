package com.carak.api.gstn;

import lombok.*;

@Builder
@Getter
@Setter
public class FileDownloadRequest {
    private String filePath;
}