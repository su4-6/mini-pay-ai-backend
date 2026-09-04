package com.minipay.agent.infrastructure.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.agent.application.service.VoiceCallService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {
    private static final List<String> SIGNAL_TYPES = List.of("WEBRTC_OFFER", "WEBRTC_ANSWER", "ICE_CANDIDATE");
    private final RealtimeSessionRegistry sessions;
    private final VoiceCallService calls;
    private final ObjectMapper json;
    public RealtimeWebSocketHandler(RealtimeSessionRegistry sessions, VoiceCallService calls, ObjectMapper json) { this.sessions = sessions; this.calls = calls; this.json = json; }
    @Override public void afterConnectionEstablished(WebSocketSession session) { sessions.connected(user(session), session); }
    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (message.getPayloadLength() > 65_536) { session.close(CloseStatus.TOO_BIG_TO_PROCESS); return; }
        JsonNode root = json.readTree(message.getPayload()); String type = root.path("type").asText();
        if (!SIGNAL_TYPES.contains(type)) return;
        UUID callId = UUID.fromString(root.path("callId").asText()); UUID actor = user(session);
        UUID peer = calls.peerForSignal(actor, callId); var call = calls.findFor(actor, callId);
        sessions.publish(peer, type, callId, call.conversationId(), root.path("payload"));
    }
    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { sessions.disconnected(user(session), session); }
    private static UUID user(WebSocketSession session) { if (session.getPrincipal() == null) throw new IllegalStateException("Authenticated principal required"); return UUID.fromString(session.getPrincipal().getName()); }
}
