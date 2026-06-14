package com.fitness.admin.community.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.fitness.admin.common.base.BaseController;
import com.fitness.admin.common.result.PageResult;
import com.fitness.admin.common.result.R;
import com.fitness.admin.community.dto.CreateCommentRequest;
import com.fitness.admin.community.dto.CreatePostRequest;
import com.fitness.admin.community.dto.CreateReportRequest;
import com.fitness.admin.community.dto.LikeRequest;
import com.fitness.admin.community.dto.LikeResponse;
import com.fitness.admin.community.dto.PostDetailResponse;
import com.fitness.admin.community.service.MiniAppCommunityService;
import com.fitness.admin.community.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 小程序社区接口
 */
@Tag(name = "小程序-社区模块")
@RestController
@RequestMapping("/miniapp/post")
@RequiredArgsConstructor
@SaCheckLogin
public class MiniAppCommunityController extends BaseController {

    private final MiniAppCommunityService miniAppCommunityService;
    private final ReportService reportService;

    @Operation(summary = "帖子列表")
    @GetMapping("/list")
    public R<PageResult<Map<String, Object>>> getPostList(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return success(miniAppCommunityService.getPostList(pageNum, pageSize));
    }

    @Operation(summary = "我的帖子")
    @GetMapping("/my")
    public R<PageResult<Map<String, Object>>> getMyPosts(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return success(miniAppCommunityService.getMyPosts(pageNum, pageSize));
    }

    @Operation(summary = "帖子详情")
    @GetMapping("/{id}")
    public R<PostDetailResponse> getPostDetail(@PathVariable Long id) {
        return success(miniAppCommunityService.getPostDetail(id));
    }

    @Operation(summary = "发布帖子")
    @PostMapping
    public R<Void> createPost(@RequestBody CreatePostRequest request) {
        miniAppCommunityService.createPost(request);
        return success();
    }

    @Operation(summary = "点赞/取消点赞")
    @PostMapping("/like")
    public R<LikeResponse> toggleLike(@RequestBody LikeRequest request) {
        return success(miniAppCommunityService.toggleLike(request.getPostId()));
    }

    @Operation(summary = "评论列表")
    @GetMapping("/{postId}/comments")
    public R<PageResult<Map<String, Object>>> getComments(
            @PathVariable Long postId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return success(miniAppCommunityService.getComments(postId, pageNum, pageSize));
    }

    @Operation(summary = "发表评论")
    @PostMapping("/{postId}/comments")
    public R<Void> createComment(@PathVariable Long postId, @RequestBody CreateCommentRequest request) {
        miniAppCommunityService.createComment(postId, request);
        return success();
    }

    @Operation(summary = "提交举报")
    @PostMapping("/report")
    public R<Void> createReport(@RequestBody CreateReportRequest request) {
        reportService.createReport(request);
        return success();
    }
}
