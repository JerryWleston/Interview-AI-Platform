package com.interview_ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.interview_ai.dto.CreateDocResp;
import com.interview_ai.dto.TaskStatusResp;
import com.interview_ai.dto.WorkerIngestReq;
import com.interview_ai.entity.KbDocument;
import com.interview_ai.entity.KbIngestTask;
import com.interview_ai.mapper.KbDocumentMapper;
import com.interview_ai.mapper.KbIngestTaskMapper;
import com.interview_ai.util.FileStore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KbAdminService {

    private final KbDocumentMapper docMapper;
    private final KbIngestTaskMapper taskMapper;

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${kb.storage.base-dir}")
    private String baseDir;

    @Value("${kb.worker.base-url}")
    private String workerBaseUrl;

    @Transactional
    public CreateDocResp createDocAndTask(Long kbId, MultipartFile file) throws Exception {
        if (kbId == null) kbId = 1L;

        String filename = file.getOriginalFilename();
        String ext = FileStore.ext(filename);

        // MVP：先只支持 txt
        if (ext == null || !ext.equals("txt")) {
            throw new IllegalArgumentException("MVP only supports .txt now");
        }

        // 1) 先插入 document（先不写 storage_path，拿到 docId 后再落盘/更新也行）
        KbDocument doc = new KbDocument();
        doc.setKbId(kbId);
        doc.setFilename(filename);
        doc.setFileExt(ext);
        doc.setMimeType(file.getContentType());
        doc.setStorageType(0);
        doc.setFileSize(file.getSize());
        doc.setStatus(0);
        docMapper.insert(doc);

        // 2) 落盘：用 docId 命名，方便排查
        String saveName = doc.getId() + "." + ext;
        String path = FileStore.saveToLocal(file, baseDir, kbId, saveName);

        // 3) 更新 document.storage_path
        doc.setStoragePath(path);
        docMapper.updateById(doc);

        // 4) 插入 task
        KbIngestTask task = new KbIngestTask();
        task.setKbId(kbId);
        task.setDocId(doc.getId());
        task.setStatus("PENDING");
        task.setStage("UPLOAD");
        task.setProgress(0);
        task.setAttempt(0);
        task.setTraceId(UUID.randomUUID().toString().replace("-", ""));
        taskMapper.insert(task);

        CreateDocResp resp = new CreateDocResp();
        resp.setDocId(doc.getId());
        resp.setTaskId(task.getId());
        resp.setStatus(task.getStatus());
        resp.setStage(task.getStage());
        return resp;
    }

    public TaskStatusResp getTask(Long taskId) {
        KbIngestTask t = taskMapper.selectById(taskId);
        if (t == null) return null;

        TaskStatusResp resp = new TaskStatusResp();
        resp.setTaskId(t.getId());
        resp.setDocId(t.getDocId());
        resp.setKbId(t.getKbId());
        resp.setStatus(t.getStatus());
        resp.setStage(t.getStage());
        resp.setProgress(t.getProgress());
        resp.setErrorCode(t.getErrorCode());
        resp.setErrorMessage(t.getErrorMessage());
        resp.setUpdatedAt(t.getUpdatedAt());
        return resp;
    }

    /**
     * start：幂等启动（只允许 PENDING -> RUNNING）
     */
    @Transactional
    public void startTask(Long taskId) {
        KbIngestTask task = taskMapper.selectById(taskId);
        if (task == null) throw new IllegalArgumentException("task not found");

        // 幂等：已经启动/结束就直接返回
        if (!"PENDING".equals(task.getStatus())) {
            return;
        }

        // 更新为 RUNNING
        task.setStatus("RUNNING");
        task.setStage("PARSE");
        task.setProgress(1);
        task.setStartedAt(LocalDateTime.now());
        task.setStartToken(UUID.randomUUID().toString());
        taskMapper.updateById(task);

        // 读取 doc 获取 filePath
        KbDocument doc = docMapper.selectById(task.getDocId());
        if (doc == null) throw new IllegalStateException("doc not found");

        // 调 worker
        WorkerIngestReq req = new WorkerIngestReq();
        req.setKbId(task.getKbId());
        req.setDocId(task.getDocId());
        req.setTaskId(task.getId());
        req.setFilePath(doc.getStoragePath());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<WorkerIngestReq> entity = new HttpEntity<>(req, headers);

        // MVP：同步调用（最简单）。你也可以改成异步线程池。
        ResponseEntity<String> r = restTemplate.exchange(
                workerBaseUrl + "/ingest",
                HttpMethod.POST,
                entity,
                String.class
        );

        if (!r.getStatusCode().is2xxSuccessful()) {
            // 立即失败：写回 FAILED（worker没接住）
            task.setStatus("FAILED");
            task.setStage("CALLBACK");
            task.setProgress(100);
            task.setErrorCode("WORKER_CALL_FAILED");
            task.setErrorMessage("Worker HTTP status: " + r.getStatusCode());
            task.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(task);
        }
    }

    /**
     * Worker 回调更新状态（MVP 最简单：DONE/FAILED 一次性）
     * 你可以后面扩展为 stage/progress 逐步上报
     */
    @Transactional
    public void updateTaskFromWorker(Long taskId, String status, String stage, Integer progress, String errorCode, String errorMessage) {
        KbIngestTask task = taskMapper.selectById(taskId);
        if (task == null) throw new IllegalArgumentException("task not found");

        task.setStatus(status);
        if (stage != null) task.setStage(stage);
        if (progress != null) task.setProgress(progress);

        task.setErrorCode(errorCode);
        task.setErrorMessage(errorMessage);

        if ("DONE".equals(status) || "FAILED".equals(status) || "CANCELED".equals(status)) {
            task.setFinishedAt(LocalDateTime.now());
            if (task.getProgress() == null || task.getProgress() < 100) task.setProgress(100);
        }
        taskMapper.updateById(task);
    }
}
