package ai.myrmec.engine.conversation;

/**
 * #88 — conversation-scoped agent availability for the chat UI. Lets a viewer
 * see, before sending, whether a worker is online to answer: {@code online} is
 * true when at least one non-DEAD instance of the conversation's pinned host is
 * connected. {@code idleCount} distinguishes "ready now" from "all busy".
 *
 * @param online        at least one connected worker for the pinned host
 * @param connectedCount connected (non-DEAD) workers
 * @param idleCount     workers currently idle and ready to take a turn
 */
public record AgentAvailabilityResponse(
        boolean online,
        int connectedCount,
        int idleCount) {
}
