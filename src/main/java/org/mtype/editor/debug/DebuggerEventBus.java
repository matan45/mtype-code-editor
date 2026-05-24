package org.mtype.editor.debug;

import javafx.application.Platform;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Typed pub/sub for debugger lifecycle and runtime events.
 * Listeners are always invoked on the JavaFX application thread.
 */
public class DebuggerEventBus {

    public enum State { IDLE, RUNNING, PAUSED, TERMINATED }

    public record StartedEvent(Path file) {}
    public record StoppedEvent(Path file, int line, String reason, String message) {}
    public record OutputEvent(String text, String category) {}
    public record StackEvent(List<Frame> frames) {}
    public record VariablesEvent(String scope, List<Variable> variables) {}
    public record ExpandedVarEvent(long reference, List<Variable> children) {}
    public record EvaluateResultEvent(String requestKey, String value, String type, long reference) {}
    public record ErrorEvent(String message) {}
    public record StateChangedEvent(State state) {}

    private final List<Consumer<StartedEvent>> startedListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<StoppedEvent>> stoppedListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<OutputEvent>> outputListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<StackEvent>> stackListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<VariablesEvent>> variablesListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ExpandedVarEvent>> expandedListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<EvaluateResultEvent>> evaluateListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ErrorEvent>> errorListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<StateChangedEvent>> stateListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> terminatedListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> resumedListeners = new CopyOnWriteArrayList<>();

    public void onStarted(Consumer<StartedEvent> l) { startedListeners.add(l); }
    public void onStopped(Consumer<StoppedEvent> l) { stoppedListeners.add(l); }
    public void onResumed(Runnable l) { resumedListeners.add(l); }
    public void onTerminated(Runnable l) { terminatedListeners.add(l); }
    public void onOutput(Consumer<OutputEvent> l) { outputListeners.add(l); }
    public void onStack(Consumer<StackEvent> l) { stackListeners.add(l); }
    public void onVariables(Consumer<VariablesEvent> l) { variablesListeners.add(l); }
    public void onExpandedVar(Consumer<ExpandedVarEvent> l) { expandedListeners.add(l); }
    public void onEvaluate(Consumer<EvaluateResultEvent> l) { evaluateListeners.add(l); }
    public void onError(Consumer<ErrorEvent> l) { errorListeners.add(l); }
    public void onStateChanged(Consumer<StateChangedEvent> l) { stateListeners.add(l); }

    public void fireStarted(StartedEvent e) { dispatch(startedListeners, e); }
    public void fireStopped(StoppedEvent e) { dispatch(stoppedListeners, e); }
    public void fireResumed() { Platform.runLater(() -> { for (Runnable r : resumedListeners) safely(r); }); }
    public void fireTerminated() { Platform.runLater(() -> { for (Runnable r : terminatedListeners) safely(r); }); }
    public void fireOutput(OutputEvent e) { dispatch(outputListeners, e); }
    public void fireStack(StackEvent e) { dispatch(stackListeners, e); }
    public void fireVariables(VariablesEvent e) { dispatch(variablesListeners, e); }
    public void fireExpandedVar(ExpandedVarEvent e) { dispatch(expandedListeners, e); }
    public void fireEvaluate(EvaluateResultEvent e) { dispatch(evaluateListeners, e); }
    public void fireError(ErrorEvent e) { dispatch(errorListeners, e); }
    public void fireStateChanged(StateChangedEvent e) { dispatch(stateListeners, e); }

    private <T> void dispatch(List<Consumer<T>> listeners, T event) {
        Platform.runLater(() -> {
            for (Consumer<T> l : listeners) {
                try { l.accept(event); } catch (Exception ignored) {}
            }
        });
    }

    private static void safely(Runnable r) {
        try { r.run(); } catch (Exception ignored) {}
    }
}
