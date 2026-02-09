package com.interview_ai.controller;

import com.interview_ai.dto.CreateDocResp;
import com.interview_ai.dto.TaskStatusResp;
import com.interview_ai.service.KbAdminService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/kb")
public class KbAdminController {

    private final KbAdminService kbAdminService;

    @PostMapping(value = "/docs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CreateDocResp createDoc(@RequestParam(value = "kbId", required = false) Long kbId,
                                   @RequestPart("file") MultipartFile file) throws Exception {
        return kbAdminService.createDocAndTask(kbId, file);
    }

    @GetMapping("/tasks/{taskId}")
    public TaskStatusResp getTask(@PathVariable Long taskId) {
        TaskStatusResp resp = kbAdminService.getTask(taskId);
        if (resp == null) throw new IllegalArgumentException("task not found");
        return resp;
    }

    @PostMapping("/tasks/{taskId}/start")
    public String start(@PathVariable Long taskId) {
        kbAdminService.startTask(taskId);
        return "OK";
    }

    // worker 回调（MVP）
    @PostMapping("/tasks/{taskId}/callback")
    public String callback(@PathVariable Long taskId, @RequestBody WorkerCallbackReq req) {
        kbAdminService.updateTaskFromWorker(
                taskId,
                req.getStatus(),
                req.getStage(),
                req.getProgress(),
                req.getErrorCode(),
                req.getErrorMessage()
        );
        return "OK";
    }

    @Data
    public static class WorkerCallbackReq {
        private String status;      // DONE / FAILED / RUNNING...
        private String stage;       // PARSE/CHUNK/EMBED/UPSERT/CALLBACK...
        private Integer progress;   // 0~100
        private String errorCode;
        private String errorMessage;
    }
}
