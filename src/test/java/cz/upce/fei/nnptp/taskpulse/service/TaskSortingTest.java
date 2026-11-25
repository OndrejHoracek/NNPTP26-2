package cz.upce.fei.nnptp.taskpulse.service;

import cz.upce.fei.nnptp.taskpulse.domain.Priority;
import cz.upce.fei.nnptp.taskpulse.domain.Project;
import cz.upce.fei.nnptp.taskpulse.domain.Task;
import cz.upce.fei.nnptp.taskpulse.domain.TaskBuilder;
import cz.upce.fei.nnptp.taskpulse.storage.InMemoryProjectRepository;
import cz.upce.fei.nnptp.taskpulse.storage.InMemoryTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TaskSortingTest {

    private static final UUID LOWER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID HIGHER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private InMemoryProjectRepository projectRepository;
    private InMemoryTaskRepository taskRepository;
    private ProjectService projectService;
    private TaskService taskService;
    private Project project;

    @BeforeEach
    void setUp() {
        projectRepository = new InMemoryProjectRepository();
        taskRepository = new InMemoryTaskRepository();
        projectService = new ProjectService(projectRepository, taskRepository);
        taskService = new TaskService(taskRepository, projectRepository);
        project = projectService.createProject("Sorting Test", "");
    }

    @Test
    void sortByTitleAscendingCaseInsensitive() {
        Task t1 = taskService.createTask(project.getId(), "banana", "", Priority.LOW, null, null);
        Task t2 = taskService.createTask(project.getId(), "Apple", "", Priority.LOW, null, null);
        Task t3 = taskService.createTask(project.getId(), "Cherry", "", Priority.LOW, null, null);

        TaskQuery query = TaskQuery.builder().sortOrder(TaskSortOrder.TITLE_ASC).build();
        List<Task> sorted = taskService.findTasks(query);

        assertThat(sorted).containsExactly(t2, t1, t3);
    }

    @Test
    void sortByPriorityDescending() {
        Task t1 = taskService.createTask(project.getId(), "T1", "", Priority.LOW, null, null);
        Task t2 = taskService.createTask(project.getId(), "T2", "", Priority.CRITICAL, null, null);
        Task t3 = taskService.createTask(project.getId(), "T3", "", Priority.MEDIUM, null, null);
        Task t4 = taskService.createTask(project.getId(), "T4", "", Priority.HIGH, null, null);

        TaskQuery query = TaskQuery.builder().sortOrder(TaskSortOrder.PRIORITY_DESC).build();
        List<Task> sorted = taskService.findTasks(query);

        assertThat(sorted).containsExactly(t2, t4, t3, t1);
    }

    @Test
    void sortByDueDateAscendingWithNullsLast() {
        LocalDate d1 = LocalDate.of(2026, 11, 1);
        LocalDate d2 = LocalDate.of(2026, 11, 10);

        Task noDate = taskService.createTask(project.getId(), "No Date", "", Priority.LOW, null, null);
        Task lateDate = taskService.createTask(project.getId(), "Late", "", Priority.LOW, null, d2);
        Task earlyDate = taskService.createTask(project.getId(), "Early", "", Priority.LOW, null, d1);

        TaskQuery query = TaskQuery.builder().sortOrder(TaskSortOrder.DUE_DATE_ASC).build();
        List<Task> sorted = taskService.findTasks(query);

        assertThat(sorted).containsExactly(earlyDate, lateDate, noDate);
    }

    @Test
    void sortByPriorityDescendingThenDueDateAscending() {
        LocalDate early = LocalDate.of(2026, 11, 1);
        LocalDate late = LocalDate.of(2026, 11, 10);

        Task critLate = taskService.createTask(project.getId(), "Crit Late", "", Priority.CRITICAL, null, late);
        Task critEarly = taskService.createTask(project.getId(), "Crit Early", "", Priority.CRITICAL, null, early);
        Task highEarly = taskService.createTask(project.getId(), "High Early", "", Priority.HIGH, null, early);

        TaskQuery query = TaskQuery.builder().sortOrder(TaskSortOrder.PRIORITY_DESC_THEN_DUE_DATE_ASC).build();
        List<Task> sorted = taskService.findTasks(query);

        assertThat(sorted).containsExactly(critEarly, critLate, highEarly);
    }

    @Test
    void sortByPriorityDescendingThenDueDateAscendingWithNullsLast() {
        LocalDate early = LocalDate.of(2026, 11, 1);
        LocalDate late = LocalDate.of(2026, 11, 10);

        Task highUndated = taskService.createTask(project.getId(), "High Undated", "", Priority.HIGH, null, null);
        Task highDated = taskService.createTask(project.getId(), "High Dated", "", Priority.HIGH, null, late);
        Task lowDated = taskService.createTask(project.getId(), "Low Dated", "", Priority.LOW, null, early);

        TaskQuery query = TaskQuery.builder().sortOrder(TaskSortOrder.PRIORITY_DESC_THEN_DUE_DATE_ASC).build();
        List<Task> sorted = taskService.findTasks(query);

        assertThat(sorted).containsExactly(highDated, highUndated, lowDated);
    }

    @ParameterizedTest
    @EnumSource(TaskSortOrder.class)
    void equalSortValuesAndTitlesAreOrderedById(TaskSortOrder sortOrder) {
        LocalDate dueDate = LocalDate.of(2026, 11, 10);
        Task higherId = saveTask(HIGHER_ID, "Write report", Priority.HIGH, dueDate);
        Task lowerId = saveTask(LOWER_ID, "Write report", Priority.HIGH, dueDate);

        TaskQuery query = TaskQuery.builder().sortOrder(sortOrder).build();
        List<Task> sorted = taskService.findTasks(query);

        assertThat(sorted).containsExactly(lowerId, higherId);
    }

    @ParameterizedTest
    @EnumSource(value = TaskSortOrder.class, names = "TITLE_ASC", mode = EnumSource.Mode.EXCLUDE)
    void equalSortValuesAreOrderedByTitleBeforeId(TaskSortOrder sortOrder) {
        LocalDate dueDate = LocalDate.of(2026, 11, 10);
        Task zebra = saveTask(LOWER_ID, "Zebra", Priority.HIGH, dueDate);
        Task alpha = saveTask(HIGHER_ID, "alpha", Priority.HIGH, dueDate);

        TaskQuery query = TaskQuery.builder().sortOrder(sortOrder).build();
        List<Task> sorted = taskService.findTasks(query);

        assertThat(sorted).containsExactly(alpha, zebra);
    }

    @Test
    void defaultOrderUsesIdWhenTitlesDifferOnlyInCase() {
        Task upperCase = saveTask(HIGHER_ID, "Write report", Priority.MEDIUM, null);
        Task lowerCase = saveTask(LOWER_ID, "write report", Priority.MEDIUM, null);

        TaskQuery query = TaskQuery.builder().build();
        List<Task> sorted = taskService.findTasks(query);

        assertThat(sorted).containsExactly(lowerCase, upperCase);
    }

    private Task saveTask(UUID id, String title, Priority priority, LocalDate dueDate) {
        return taskRepository.save(new TaskBuilder(project.getId(), title)
                .id(id)
                .priority(priority)
                .dueDate(dueDate)
                .build());
    }
}
