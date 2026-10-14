/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link LocalSessionTurnGate} fair per-key mutual exclusion. */
class LocalSessionTurnGateTest {

    @Test
    void serializesConcurrentTurnsForSameKey() throws Exception {
        LocalSessionTurnGate gate = new LocalSessionTurnGate();
        String key = "session-a";
        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        CountDownLatch firstAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);

        Thread t1 =
                new Thread(
                        () -> {
                            try {
                                TurnLease lease = gate.acquire(key);
                                int now = concurrent.incrementAndGet();
                                maxConcurrent.updateAndGet(prev -> Math.max(prev, now));
                                firstAcquired.countDown();
                                releaseFirst.await(5, TimeUnit.SECONDS);
                                concurrent.decrementAndGet();
                                lease.close();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });

        Thread t2 =
                new Thread(
                        () -> {
                            try {
                                firstAcquired.await(5, TimeUnit.SECONDS);
                                TurnLease lease = gate.acquire(key);
                                int now = concurrent.incrementAndGet();
                                maxConcurrent.updateAndGet(prev -> Math.max(prev, now));
                                concurrent.decrementAndGet();
                                lease.close();
                                secondFinished.countDown();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });

        t1.start();
        t2.start();
        assertTrue(firstAcquired.await(5, TimeUnit.SECONDS));
        assertEquals(1, concurrent.get(), "first holder should be the only active turn");
        releaseFirst.countDown();
        assertTrue(secondFinished.await(5, TimeUnit.SECONDS));
        assertTrue(maxConcurrent.get() <= 1, "at most one turn should hold the gate");
        t1.join(5000);
        t2.join(5000);
    }

    @Test
    void allowsParallelTurnsForDifferentKeys() throws Exception {
        LocalSessionTurnGate gate = new LocalSessionTurnGate();
        CountDownLatch bothInside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<Exception> errors = new ArrayList<>();

        Runnable worker1 =
                () -> {
                    try {
                        TurnLease lease = gate.acquire("key-1");
                        bothInside.countDown();
                        release.await(5, TimeUnit.SECONDS);
                        lease.close();
                    } catch (Exception e) {
                        errors.add(e);
                    }
                };
        Runnable worker2 =
                () -> {
                    try {
                        TurnLease lease = gate.acquire("key-2");
                        bothInside.countDown();
                        release.await(5, TimeUnit.SECONDS);
                        lease.close();
                    } catch (Exception e) {
                        errors.add(e);
                    }
                };

        Thread t1 = new Thread(worker1);
        Thread t2 = new Thread(worker2);
        t1.start();
        t2.start();
        assertTrue(bothInside.await(5, TimeUnit.SECONDS), "different keys should not block");
        assertTrue(errors.isEmpty(), errors.toString());
        release.countDown();
        t1.join(5000);
        t2.join(5000);
    }

    @Test
    void isRunningReflectsHeldLease() throws Exception {
        LocalSessionTurnGate gate = new LocalSessionTurnGate();
        String key = "running-key";
        assertFalse(gate.isRunning(key));

        TurnLease lease = gate.acquire(key);
        assertTrue(gate.isRunning(key));
        lease.close();
        assertFalse(gate.isRunning(key));
    }

    @Test
    void evictsIdleKeysSoTheMapDoesNotGrowWithoutBound() throws Exception {
        LocalSessionTurnGate gate = new LocalSessionTurnGate();

        for (int i = 0; i < 50; i++) {
            gate.acquire("session-" + i).close();
        }

        ConcurrentHashMap<?, ?> gates = internalGates(gate);
        assertEquals(
                0,
                gates.size(),
                () -> "closed leases must not leave stale gate entries behind: " + gates.size());
    }

    @Test
    void closeIsIdempotentSoARepeatedCloseCannotOpenTheGateTwice() throws Exception {
        LocalSessionTurnGate gate = new LocalSessionTurnGate();
        String key = "double-close";

        TurnLease lease = gate.acquire(key);
        lease.close();
        // TurnLease documents that close() is idempotent. Each extra release leaves one more
        // permit behind, which would let a later acquirer hold the key at the same time.
        lease.close();
        lease.close();

        TurnLease second = gate.acquire(key);
        assertTrue(gate.isRunning(key), "the second lease must be the only holder");
        second.close();
        assertFalse(gate.isRunning(key));
    }

    @Test
    void neverOverlapsTwoHoldersForOneKeyUnderChurn() throws Exception {
        LocalSessionTurnGate gate = new LocalSessionTurnGate();
        String key = "churn";
        int threads = 8;
        int rounds = 200;

        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            Thread worker =
                    new Thread(
                            () -> {
                                try {
                                    go.await();
                                    for (int r = 0; r < rounds; r++) {
                                        TurnLease lease = gate.acquire(key);
                                        int now = inside.incrementAndGet();
                                        maxInside.accumulateAndGet(now, Math::max);
                                        Thread.yield();
                                        inside.decrementAndGet();
                                        lease.close();
                                    }
                                } catch (Throwable e) {
                                    errors.add(e);
                                }
                            });
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }

        go.countDown();
        for (Thread worker : workers) {
            worker.join(30_000);
        }

        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(1, maxInside.get(), "at most one holder per key, even under heavy churn");
    }

    @Test
    void preInterruptedAcquireDoesNotRetainAnIdleKey() throws Exception {
        LocalSessionTurnGate gate = new LocalSessionTurnGate();
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, () -> gate.acquire("interrupted"));
        } finally {
            Thread.interrupted();
        }
        assertTrue(internalGates(gate).isEmpty());
        try (TurnLease ignored = gate.acquire("interrupted")) {
            assertTrue(gate.isRunning("interrupted"));
        }
        assertTrue(internalGates(gate).isEmpty());
    }

    @Test
    void interruptedWaiterRetiresWithoutRemovingTheHolderOrOtherWaiter() throws Exception {
        LocalSessionTurnGate gate = new LocalSessionTurnGate();
        String key = "interrupted-waiter";
        TurnLease holder = gate.acquire(key);
        Object entry = internalGates(gate).get(key);
        Field semaphoreField = entry.getClass().getDeclaredField("semaphore");
        semaphoreField.setAccessible(true);
        Semaphore semaphore = (Semaphore) semaphoreField.get(entry);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch survivorAcquired = new CountDownLatch(1);
        CountDownLatch releaseSurvivor = new CountDownLatch(1);

        Thread cancelled =
                new Thread(
                        () -> {
                            try (TurnLease ignored = gate.acquire(key)) {
                                error.compareAndSet(
                                        null,
                                        new AssertionError("cancelled waiter acquired the gate"));
                            } catch (InterruptedException expected) {
                                interrupted.set(true);
                            } catch (Throwable failure) {
                                error.compareAndSet(null, failure);
                            }
                        });
        Thread survivor =
                new Thread(
                        () -> {
                            try (TurnLease ignored = gate.acquire(key)) {
                                survivorAcquired.countDown();
                                if (!releaseSurvivor.await(5, TimeUnit.SECONDS)) {
                                    throw new AssertionError("surviving holder was not released");
                                }
                            } catch (Throwable failure) {
                                error.compareAndSet(null, failure);
                            }
                        });
        try {
            cancelled.start();
            awaitQueueLength(semaphore, 1);
            survivor.start();
            awaitQueueLength(semaphore, 2);
            cancelled.interrupt();
            cancelled.join(5000);
            assertFalse(cancelled.isAlive());
            assertTrue(interrupted.get());
            assertEquals(1, internalGates(gate).size());
            assertTrue(gate.isRunning(key));
            assertEquals(1, survivorAcquired.getCount(), "the original holder is still active");

            holder.close();
            assertTrue(survivorAcquired.await(5, TimeUnit.SECONDS));
            assertTrue(gate.isRunning(key));
            releaseSurvivor.countDown();
            survivor.join(5000);
            assertFalse(survivor.isAlive());
            assertEquals(null, error.get());
            assertFalse(gate.isRunning(key));
            assertTrue(
                    internalGates(gate).isEmpty(), "all holder and waiter references must retire");
        } finally {
            holder.close();
            releaseSurvivor.countDown();
            cancelled.interrupt();
            survivor.interrupt();
            cancelled.join(5000);
            survivor.join(5000);
        }
    }

    private static void awaitQueueLength(Semaphore semaphore, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (semaphore.getQueueLength() != expected && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertEquals(expected, semaphore.getQueueLength(), "waiters must reach the semaphore");
    }

    private static ConcurrentHashMap<?, ?> internalGates(LocalSessionTurnGate gate)
            throws Exception {
        Field field = LocalSessionTurnGate.class.getDeclaredField("gates");
        field.setAccessible(true);
        return (ConcurrentHashMap<?, ?>) field.get(gate);
    }
}
