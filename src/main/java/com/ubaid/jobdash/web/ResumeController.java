package com.ubaid.jobdash.web;

import com.ubaid.jobdash.resume.ResumeService;
import com.ubaid.jobdash.web.dto.ResumeResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * REST surface for uploading and managing resumes. The extracted text these produce is what a
 * later task sends to Claude to compare against job descriptions - see {@link ResumeService}.
 */
@RestController
public class ResumeController {

    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    @PostMapping("/api/resumes")
    public ResumeResponse upload(@RequestPart("file") MultipartFile file,
                                  @RequestParam(name = "name", required = false) String name) {
        return ResumeResponse.of(resumeService.upload(file, name));
    }

    @GetMapping("/api/resumes")
    public List<ResumeResponse> list() {
        return resumeService.list().stream().map(ResumeResponse::of).toList();
    }

    @PostMapping("/api/resumes/{id}/default")
    public void setDefault(@PathVariable long id) {
        resumeService.setDefault(id);
    }

    @DeleteMapping("/api/resumes/{id}")
    public void delete(@PathVariable long id) {
        resumeService.delete(id);
    }

    @GetMapping("/api/resumes/{id}/text")
    public String text(@PathVariable long id) {
        return resumeService.text(id);
    }
}
