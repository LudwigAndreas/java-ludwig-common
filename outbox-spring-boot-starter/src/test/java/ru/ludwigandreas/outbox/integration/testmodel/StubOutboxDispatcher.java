package ru.ludwigandreas.outbox.integration.testmodel;

import ru.ludwigandreas.outbox.dispatch.DispatchResult;
import ru.ludwigandreas.outbox.dispatch.OutboxDispatcher;
import ru.ludwigandreas.outbox.entity.OutboxMessage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public class StubOutboxDispatcher implements OutboxDispatcher {

    private final List<OutboxMessage> dispatched = new CopyOnWriteArrayList<>();
    private volatile Function<OutboxMessage, DispatchResult> resultFunction = message -> DispatchResult.success();

    @Override
    public String transport() {
        return "STUB";
    }

    @Override
    public DispatchResult dispatch(OutboxMessage message) {
        dispatched.add(message);
        return resultFunction.apply(message);
    }

    public List<OutboxMessage> dispatched() {
        return dispatched;
    }

    public void setResultFunction(Function<OutboxMessage, DispatchResult> resultFunction) {
        this.resultFunction = resultFunction;
    }

    public void reset() {
        dispatched.clear();
        resultFunction = message -> DispatchResult.success();
    }
}
