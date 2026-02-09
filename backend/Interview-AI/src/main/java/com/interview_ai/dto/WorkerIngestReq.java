package com.interview_ai.dto;

import lombok.Data;

@Data
public class WorkerIngestReq {
    private Long kbId;
    private Long docId;
    private Long taskId;
    private String filePath;
}
