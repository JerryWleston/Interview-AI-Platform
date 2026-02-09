package com.interview_ai.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("kb_document")
public class KbDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;

    private String filename;
    private String fileExt;
    private String mimeType;

    private Integer storageType;   // 0=local
    private String storagePath;

    private Long fileSize;
    private String sha256;

    private Integer status; // 0=ACTIVE,1=DELETED

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
