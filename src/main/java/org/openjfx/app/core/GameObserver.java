package org.openjfx.app.core;

public interface GameObserver {
    void onEntityDeath(String message);
    void onActionOccurred(String actor, String action, String target);
}