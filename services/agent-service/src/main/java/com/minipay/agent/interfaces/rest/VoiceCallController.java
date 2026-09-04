package com.minipay.agent.interfaces.rest;

import com.minipay.agent.application.service.VoiceCallService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/agent/calls")
public class VoiceCallController {
    private final VoiceCallService service;
    public VoiceCallController(VoiceCallService service) { this.service = service; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public CallResponse create(@Valid @RequestBody CreateCallRequest body, @AuthenticationPrincipal Jwt jwt) { return run(() -> service.create(user(jwt), body.conversationId())); }
    @PostMapping("/{id}/accept") public CallResponse accept(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) { return run(() -> service.accept(user(jwt), id)); }
    @PostMapping("/{id}/reject") public CallResponse reject(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) { return run(() -> service.reject(user(jwt), id)); }
    @PostMapping("/{id}/cancel") public CallResponse cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) { return run(() -> service.cancel(user(jwt), id)); }
    @PostMapping("/{id}/end") public CallResponse end(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) { return run(() -> service.end(user(jwt), id)); }
    @GetMapping("/ice-servers") public IceResponse ice(@AuthenticationPrincipal Jwt jwt) { var value = service.ice(user(jwt)); return new IceResponse(value.urls(), value.username(), value.credential(), value.expiresAtEpochSeconds()); }
    private CallResponse run(java.util.function.Supplier<VoiceCallService.Call> action) { try { var c = action.get(); return new CallResponse(c.id(), c.conversationId(), c.callerId(), c.calleeId(), c.status(), c.createdAt(), c.answeredAt(), c.endedAt()); } catch (VoiceCallService.CallException e) { HttpStatus status = e.code.equals("CALL_NOT_FOUND") ? HttpStatus.NOT_FOUND : e.code.equals("CALL_FORBIDDEN") ? HttpStatus.FORBIDDEN : e.code.equals("CALLEE_BUSY") || e.code.equals("CALLEE_OFFLINE") ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_ENTITY; throw new ResponseStatusException(status, e.code, e); } }
    private static UUID user(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    public record CreateCallRequest(@NotBlank String conversationId) {}
    public record CallResponse(UUID id, String conversationId, UUID callerId, UUID calleeId, String status, Instant createdAt, Instant answeredAt, Instant endedAt) {}
    public record IceResponse(List<String> urls, String username, String credential, long expiresAtEpochSeconds) {}
}
