package colossus.physics;

import colossus.physics.api.*;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Owns one backend on a dedicated physics thread running at a fixed rate (default 60 Hz).
 * <p>
 * Blueprint tick pipeline: the game thread pushes commands (forces, motor targets, new bodies) onto a queue; the
 * physics thread drains them, steps, and publishes an immutable {@link Snapshot}. The game thread only ever reads the
 * latest completed snapshot, so neither side waits on the other during normal play.
 * <p>
 * For tests and headless benchmarks, construct with {@code threaded = false} and call {@link #stepNow(int)}.
 */
public final class PhysicsWorld implements AutoCloseable {

    /** Immutable result of one physics step. */
    public record Snapshot(long step, double time, Map<Integer, BodyState> bodies, Map<Integer, ConstraintState> constraints) {
        public static final Snapshot EMPTY = new Snapshot(0, 0, Map.of(), Map.of());
        public BodyState body(BodyHandle h) { return bodies.getOrDefault(h.id(), BodyState.MISSING); }
        public ConstraintState constraint(ConstraintHandle h) { return constraints.getOrDefault(h.id(), ConstraintState.MISSING); }
    }

    /**
     * Code that runs on the physics thread before every step, after queued commands (controllers that must act at the
     * physics rate, like gyros and, later, joint servos). Must not touch Minecraft objects.
     */
    public interface StepController {
        void beforeStep(PhysicsBackend backend, double dt);
    }

    private final PhysicsBackend backend;
    private final java.util.List<StepController> controllers = new java.util.ArrayList<>();
    private final double dt;
    private final ConcurrentLinkedQueue<Consumer<PhysicsBackend>> commands = new ConcurrentLinkedQueue<>();
    private final Set<Integer> trackedBodies = new LinkedHashSet<>();
    private final Set<Integer> trackedConstraints = new LinkedHashSet<>();
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile boolean running;
    private volatile double lastStepMillis;
    private Thread thread;
    private long stepIndex;

    public PhysicsWorld(PhysicsBackend backend, double hz, boolean threaded, String name) {
        this.backend = backend;
        this.dt = 1.0 / hz;
        if (threaded) {
            running = true;
            thread = new Thread(this::loop, name);
            thread.setDaemon(true);
            thread.start();
        }
    }

    public PhysicsBackend backendForTests() { return backend; }

    public Snapshot snapshot() { return snapshot; }

    /** Wall time of the last step in ms (performance budget: < 4 ms for 10 walkers). */
    public double lastStepMillis() { return lastStepMillis; }

    /** Fire-and-forget command executed on the physics thread before the next step. */
    public void enqueue(Consumer<PhysicsBackend> cmd) { commands.add(cmd); }

    /** Command with a result (body/constraint creation). Complete before the next step. */
    public <T> CompletableFuture<T> submit(Function<PhysicsBackend, T> fn) {
        CompletableFuture<T> f = new CompletableFuture<>();
        commands.add(b -> {
            try { f.complete(fn.apply(b)); } catch (Throwable t) { f.completeExceptionally(t); }
        });
        return f;
    }

    public CompletableFuture<BodyHandle> createBody(BodyDesc desc) {
        return submit(b -> { BodyHandle h = b.createBody(desc); trackedBodies.add(h.id()); return h; });
    }

    public CompletableFuture<ConstraintHandle> addConstraint(ConstraintDesc desc) {
        return submit(b -> { ConstraintHandle h = b.addConstraint(desc); trackedConstraints.add(h.id()); return h; });
    }

    /** Bodies and joints created together in one physics-thread command. */
    public record Group(java.util.List<BodyHandle> bodies, java.util.List<ConstraintHandle> constraints) {}

    /**
     * Create a whole Colossus in one command, so the game thread waits for at most one physics step.
     * {@code joints} receives the new body handles and returns constraint descriptors (null entries are skipped and
     * yield null handles, keeping indices aligned).
     */
    public CompletableFuture<Group> createGroup(java.util.List<BodyDesc> bodies,
                                                Function<java.util.List<BodyHandle>, java.util.List<ConstraintDesc>> joints) {
        return submit(b -> {
            java.util.List<BodyHandle> bh = new java.util.ArrayList<>();
            for (BodyDesc d : bodies) { BodyHandle h = b.createBody(d); trackedBodies.add(h.id()); bh.add(h); }
            java.util.List<ConstraintHandle> ch = new java.util.ArrayList<>();
            for (ConstraintDesc d : joints.apply(bh)) {
                if (d == null) { ch.add(null); continue; }
                ConstraintHandle h = b.addConstraint(d);
                trackedConstraints.add(h.id());
                ch.add(h);
            }
            return new Group(bh, ch);
        });
    }

    /** Runs {@code c} before every step from the next one on. */
    public void addController(StepController c) { enqueue(b -> controllers.add(c)); }

    public void removeController(StepController c) { enqueue(b -> controllers.remove(c)); }

    public void removeBody(BodyHandle h) { enqueue(b -> { b.removeBody(h); trackedBodies.remove(h.id()); }); }

    public void removeConstraint(ConstraintHandle h) { enqueue(b -> { b.removeConstraint(h); trackedConstraints.remove(h.id()); }); }

    /** Synchronous stepping for unthreaded worlds. */
    public void stepNow(int steps) {
        if (thread != null) throw new IllegalStateException("threaded world steps itself");
        for (int i = 0; i < steps; i++) doStep();
    }

    private void loop() {
        long period = (long) (dt * 1e9);
        long next = System.nanoTime();
        while (running) {
            doStep();
            next += period;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                try { Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000)); } catch (InterruptedException e) { return; }
            } else if (sleep < -period * 5) {
                next = System.nanoTime(); // fell far behind: drop steps instead of spiralling
            }
        }
    }

    private void doStep() {
        long t0 = System.nanoTime();
        Consumer<PhysicsBackend> c;
        while ((c = commands.poll()) != null) c.accept(backend);
        for (StepController sc : controllers) sc.beforeStep(backend, dt);
        backend.step(dt);
        stepIndex++;
        Map<Integer, BodyState> bs = new HashMap<>();
        for (int id : trackedBodies) bs.put(id, backend.bodyState(new BodyHandle(id)));
        Map<Integer, ConstraintState> cs = new HashMap<>();
        for (int id : trackedConstraints) cs.put(id, backend.constraintState(new ConstraintHandle(id)));
        snapshot = new Snapshot(stepIndex, stepIndex * dt, Map.copyOf(bs), Map.copyOf(cs));
        lastStepMillis = (System.nanoTime() - t0) / 1e6;
    }

    @Override public void close() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try { thread.join(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        backend.close();
    }
}
