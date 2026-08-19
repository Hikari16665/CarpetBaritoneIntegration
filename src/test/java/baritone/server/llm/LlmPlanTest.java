package baritone.server.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LlmPlanTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void parsesMultipleTasksAndDependencies() throws Exception {
        LlmPlan plan = LlmPlan.parse(MAPPER.readTree("""
                {"summary":"mine and deliver","tasks":[
                  {"id":"go","action":"goto","arguments":["1","64","2"],
                   "depends_on":[],"timeout_seconds":null},
                  {"id":"mine","action":"mine","arguments":["diamond_ore","16"],
                   "depends_on":[{"task_id":"go","mode":"required"}],
                   "timeout_seconds":600}
                ]}
                """));

        assertEquals(2, plan.tasks().size());
        assertEquals(LlmDependency.Mode.REQUIRED,
                plan.tasks().get(1).dependencies().getFirst().mode());
    }

    @Test
    public void requiredFailureSkipsButOptionalFailureRuns() throws Exception {
        LlmPlan plan = new LlmPlan("plan", "", List.of(
                task("first", "goto", List.of(), null),
                task("required", "status", List.of(dep(
                        "first", LlmDependency.Mode.REQUIRED)), null),
                task("optional", "status", List.of(dep(
                        "first", LlmDependency.Mode.OPTIONAL)), null)));
        LlmPlanExecution execution = new LlmPlanExecution(
                LlmPlanValidator.validate(plan, registry(), 24));

        assertEquals("first", execution.nextReady().orElseThrow().id());
        execution.started("first", 1);
        execution.finish("first", LlmTaskStatus.FAILED,
                "NO_PATH", "failed", 2, Map.of());
        assertEquals("optional", execution.nextReady().orElseThrow().id());
        assertEquals(LlmTaskStatus.SKIPPED,
                execution.results().get("required").status());
    }

    @Test
    public void rejectsCyclesAndContinuousDependenciesWithoutTimeout()
            throws Exception {
        LlmPlan cyclic = new LlmPlan("cycle", "", List.of(
                task("a", "status", List.of(dep(
                        "b", LlmDependency.Mode.REQUIRED)), null),
                task("b", "status", List.of(dep(
                        "a", LlmDependency.Mode.REQUIRED)), null)));
        assertThrows(IllegalArgumentException.class, () ->
                LlmPlanValidator.validate(cyclic, registry(), 24));

        LlmPlan continuous = new LlmPlan("continuous", "", List.of(
                task("follow", "follow", List.of(), null),
                task("after", "status", List.of(dep(
                        "follow", LlmDependency.Mode.OPTIONAL)), null)));
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () ->
                        LlmPlanValidator.validate(continuous, registry(), 24));
        assertTrue(error.getMessage().contains("timeout_seconds"));
    }

    @Test
    public void rejectsDuplicateMissingAndSelfDependencies() throws Exception {
        assertThrows(IllegalArgumentException.class, () ->
                LlmPlanValidator.validate(new LlmPlan("duplicate", "", List.of(
                        task("same", "status", List.of(), null),
                        task("same", "status", List.of(), null))), registry(), 24));
        assertThrows(IllegalArgumentException.class, () ->
                LlmPlanValidator.validate(new LlmPlan("missing", "", List.of(
                        task("one", "status", List.of(dep(
                                "absent", LlmDependency.Mode.REQUIRED)), null))),
                        registry(), 24));
        assertThrows(IllegalArgumentException.class, () ->
                LlmPlanValidator.validate(new LlmPlan("self", "", List.of(
                        task("one", "status", List.of(dep(
                                "one", LlmDependency.Mode.REQUIRED)), null))),
                        registry(), 24));
    }

    @Test
    public void readyTasksRemainInDeclarationOrder() throws Exception {
        LlmPlanExecution execution = new LlmPlanExecution(
                LlmPlanValidator.validate(new LlmPlan("ordered", "", List.of(
                        task("first", "status", List.of(), null),
                        task("second", "status", List.of(), null))), registry(), 24));
        assertEquals("first", execution.nextReady().orElseThrow().id());
        execution.started("first", 1);
        execution.finish("first", LlmTaskStatus.SUCCEEDED,
                "OK", "ok", 2, Map.of());
        assertEquals("second", execution.nextReady().orElseThrow().id());
    }

    @Test
    public void optionalRunsAfterTimedOutOrSkippedPrerequisite() throws Exception {
        LlmPlanExecution execution = new LlmPlanExecution(
                LlmPlanValidator.validate(new LlmPlan("optional", "", List.of(
                        task("root", "goto", List.of(), null),
                        task("required", "status", List.of(dep(
                                "root", LlmDependency.Mode.REQUIRED)), null),
                        task("optional", "status", List.of(dep(
                                "required", LlmDependency.Mode.OPTIONAL)), null))),
                        registry(), 24));
        execution.nextReady();
        execution.started("root", 1);
        execution.finish("root", LlmTaskStatus.TIMED_OUT,
                "TIMEOUT", "timeout", 2, Map.of());
        assertEquals("optional", execution.nextReady().orElseThrow().id());
        assertEquals(LlmTaskStatus.SKIPPED,
                execution.results().get("required").status());
    }

    @Test
    public void pausedLifecycleDoesNotBecomeSilentProcessFailure() {
        TaskLifecycleTracker tracker = new TaskLifecycleTracker();
        tracker.start("plan", "build",
                LlmCapabilityRegistry.CompletionMode.FINITE,
                60, 0, true);
        tracker.pause("MISSING_MATERIALS", "missing glass");
        tracker.tick(20, false);
        assertEquals(LlmTaskStatus.PAUSED, tracker.snapshot().status());
        assertEquals(null, tracker.pollTerminal());
        tracker.resume();
        tracker.tick(21, false);
        assertEquals("PROCESS_ENDED_WITHOUT_RESULT",
                tracker.pollTerminal().code());
    }

    @Test
    public void finiteTaskTimeoutHasFloorButContinuousDurationIsExact() {
        LlmPlanTask finite = task("go", "goto", List.of(), 30);
        LlmPlanTask continuous = task("follow", "follow", List.of(), 30);
        assertEquals(Integer.valueOf(300),
                LlmPlanCoordinator.effectiveTimeoutSeconds(
                        finite, capability("goto",
                                LlmCapabilityRegistry.CompletionMode.FINITE)));
        assertEquals(Integer.valueOf(30),
                LlmPlanCoordinator.effectiveTimeoutSeconds(
                        continuous, capability("follow",
                                LlmCapabilityRegistry.CompletionMode.CONTINUOUS)));
        assertEquals(null, LlmPlanCoordinator.effectiveTimeoutSeconds(
                task("unbounded", "goto", List.of(), null),
                capability("goto",
                        LlmCapabilityRegistry.CompletionMode.FINITE)));
    }

    private static LlmPlanTask task(
            String id, String action, List<LlmDependency> dependencies,
            Integer timeout) {
        return new LlmPlanTask(id, action, List.of(), dependencies, timeout);
    }

    private static LlmDependency dep(
            String id, LlmDependency.Mode mode) {
        return new LlmDependency(id, mode);
    }

    private static LlmCapabilityRegistry registry() throws Exception {
        Map<String, LlmCapabilityRegistry.Capability> capabilities =
                new LinkedHashMap<>();
        capabilities.put("goto", capability("goto",
                LlmCapabilityRegistry.CompletionMode.FINITE));
        capabilities.put("status", capability("status",
                LlmCapabilityRegistry.CompletionMode.INSTANT));
        capabilities.put("follow", capability("follow",
                LlmCapabilityRegistry.CompletionMode.CONTINUOUS));
        Constructor<LlmCapabilityRegistry> constructor =
                LlmCapabilityRegistry.class.getDeclaredConstructor(Map.class);
        constructor.setAccessible(true);
        return constructor.newInstance(capabilities);
    }

    private static LlmCapabilityRegistry.Capability capability(
            String name, LlmCapabilityRegistry.CompletionMode mode) {
        return new LlmCapabilityRegistry.Capability(
                name, List.of(name), name, mode, false, false);
    }
}
