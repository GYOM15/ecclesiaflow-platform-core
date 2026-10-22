package com.ecclesiaflow.platform.events.retry;

import org.springframework.amqp.core.Declarables;

import java.util.ArrayList;
import java.util.List;

/**
 * Opts one consumer queue in to delayed retries. Declared as a bean, {@code RabbitAdmin} declares its
 * stages, the dead-letter gauge leaves them out, and the platform's recoverer and verifier pick it up.
 * The consumer queue keeps its dead-letter route: that is where a delivery goes once its last stage
 * has failed.
 */
public class DelayedRetryTopology extends Declarables {

    private final String queue;
    private final DelayedRetrySchedule schedule;
    private final List<DelayedRetryQueue> retryQueues;

    /** {@link DelayedRetrySchedule#STANDARD}: 1 min, 5 min, 30 min and 2 h. */
    public DelayedRetryTopology(String queue) {
        this(queue, DelayedRetrySchedule.STANDARD);
    }

    public DelayedRetryTopology(String queue, DelayedRetrySchedule schedule) {
        this(queue, schedule, stagesOf(queue, schedule));
    }

    private DelayedRetryTopology(String queue, DelayedRetrySchedule schedule, List<DelayedRetryQueue> retryQueues) {
        super(retryQueues);
        this.queue = queue;
        this.schedule = schedule;
        this.retryQueues = retryQueues;
    }

    /** The consumer queue the stages send their messages back to. */
    public String queue() {
        return queue;
    }

    public DelayedRetrySchedule schedule() {
        return schedule;
    }

    public List<DelayedRetryQueue> retryQueues() {
        return retryQueues;
    }

    public DelayedRetryQueue retryQueue(int attempt) {
        if (!schedule.hasStage(attempt)) {
            throw new IllegalArgumentException("Queue '" + queue + "' has no retry stage " + attempt);
        }
        return retryQueues.get(attempt - 1);
    }

    private static List<DelayedRetryQueue> stagesOf(String queue, DelayedRetrySchedule schedule) {
        if (queue == null || queue.isBlank()) {
            throw new IllegalArgumentException("A delayed retry topology names its consumer queue");
        }
        if (schedule == null) {
            throw new IllegalArgumentException("A delayed retry topology needs a schedule");
        }
        List<DelayedRetryQueue> stages = new ArrayList<>();
        for (int attempt = 1; attempt <= schedule.stages(); attempt++) {
            stages.add(new DelayedRetryQueue(queue, attempt, schedule.delay(attempt)));
        }
        return List.copyOf(stages);
    }
}
