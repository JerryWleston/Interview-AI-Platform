package com.interview_ai.dto;

import lombok.Data;

@Data
public class CreateDocResp {
    private Long docId;
    private Long taskId;
    private String status;
    private String stage;
}
