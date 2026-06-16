package org.techtown.temi_test;

import java.util.EnumSet;

public class RobotStateMachine {

    public enum State {
        IDLE_AT_BASE,
        MOVING_TO_USER,
        CHECKING_USER,
        READY_FOR_GAME,
        PLAYING_GAME,
        STOPPING_GAME,
        RETURNING_TO_BASE,
        ERROR,
        EMERGENCY_STOPPED
    }

    public interface Listener {
        void onStateChanged(State state);
    }

    private State state = State.IDLE_AT_BASE;
    private final Listener listener;

    public RobotStateMachine(Listener listener) {
        this.listener = listener;
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized boolean transitionTo(State next) {
        if (next == state) {
            return true;
        }
        if (next == State.ERROR || next == State.EMERGENCY_STOPPED || isAllowed(state, next)) {
            state = next;
            if (listener != null) {
                listener.onStateChanged(next);
            }
            return true;
        }
        return false;
    }

    private boolean isAllowed(State current, State next) {
        switch (current) {
            case IDLE_AT_BASE:
                return EnumSet.of(
                        State.MOVING_TO_USER,
                        State.CHECKING_USER,
                        State.RETURNING_TO_BASE
                ).contains(next);
            case MOVING_TO_USER:
                return next == State.CHECKING_USER || next == State.RETURNING_TO_BASE;
            case CHECKING_USER:
                return next == State.READY_FOR_GAME || next == State.RETURNING_TO_BASE;
            case READY_FOR_GAME:
                return EnumSet.of(
                        State.CHECKING_USER,
                        State.PLAYING_GAME,
                        State.RETURNING_TO_BASE
                ).contains(next);
            case PLAYING_GAME:
                return next == State.STOPPING_GAME || next == State.RETURNING_TO_BASE;
            case STOPPING_GAME:
                return next == State.READY_FOR_GAME || next == State.RETURNING_TO_BASE;
            case RETURNING_TO_BASE:
                return next == State.IDLE_AT_BASE;
            case ERROR:
                return next == State.IDLE_AT_BASE || next == State.RETURNING_TO_BASE;
            case EMERGENCY_STOPPED:
                return next == State.IDLE_AT_BASE || next == State.RETURNING_TO_BASE;
            default:
                return false;
        }
    }
}
