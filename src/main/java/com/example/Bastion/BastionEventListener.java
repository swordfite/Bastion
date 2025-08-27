package com.example.bastion;

public interface BastionEventListener {
    void onSuspiciousRequest(String mod, String host, String url, String reason, BastionCore.Severity severity);
    void onDecision(String mod, String host, String url, BastionCore.DecisionState state);
    void onSessionDetected(String mod, String payload, BastionCore.Severity severity);
}
