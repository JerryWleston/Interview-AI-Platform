package com.interview_ai.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TaskStatusResp {
    private Long taskId;
    private Long docId;
    private Long kbId;
    private String status;
    private String stage;
    private Integer progress;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime updatedAt;
}
