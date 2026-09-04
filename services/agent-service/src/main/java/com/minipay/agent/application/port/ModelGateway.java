package com.minipay.agent.application.port;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public interface ModelGateway {
    void streamText(ModelRequest request, Consumer<String> deltaConsumer);

    default PendingTransferTurn classifyPendingTransferTurn(
            PendingTransferClassificationRequest request) {
        return PendingTransferTurn.NEW_TOPIC;
    }

    default Optional<MemoryCandidate> classifyMemory(MemoryClassificationRequest request) {
        return Optional.empty();
    }

    record ModelRequest(String systemPrompt, List<ContextMessage> history, String userMessage,
                        List<String> relevantMemories, String promptVersion) {
        public ModelRequest {
            history = history == null ? List.of() : List.copyOf(history);
            relevantMemories = relevantMemories == null ? List.of() : List.copyOf(relevantMemories);
        }
    }

    record ContextMessage(Role role, String text) {
        public enum Role { USER, ASSISTANT }
    }

    record MemoryClassificationRequest(
            List<ContextMessage> history, String userMessage, String promptVersion) {
        public MemoryClassificationRequest {
            history = history == null ? List.of() : List.copyOf(history);
        }
    }

    record MemoryCandidate(
            boolean candidate,
            String type,
            String displayValue,
            boolean longTerm,
            String evidence) {
    }

    record PendingTransferClassificationRequest(
            String userMessage, List<String> awaitingFields, String promptVersion) {
        public PendingTransferClassificationRequest {
            awaitingFields = awaitingFields == null ? List.of() : List.copyOf(awaitingFields);
        }
    }

    enum PendingTransferTurn {
        TRANSFER_SLOT,
        NEW_TOPIC
    }
}
