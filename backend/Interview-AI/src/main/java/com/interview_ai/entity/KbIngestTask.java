package com.interview_ai.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("kb_ingest_task")
public class KbIngestTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;
    private Long docId;

    private String status;   // PENDING/RUNNING/DONE/FAILED/CANCELED
    private String stage;    // UPLOAD/PARSE/CHUNK/EMBED/UPSERT/CALLBACK
    private Integer progress; // 0~100

    private Integer attempt;

    private String lockedBy;
    private LocalDateTime lockedAt;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    private String errorCode;
    private String errorMessage;

    private String traceId;
    private String startToken;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
